package com.wavesplatform.http

// [WAIT] import cats.kernel.Monoid
import com.wavesplatform.account.{Address, AddressOrAlias}
import com.wavesplatform.api.http.{AddressApiRoute, ApiKeyNotValid}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, Base64, EitherExt2}
import com.wavesplatform.http.ApiMarshallers._
import com.wavesplatform.lang.directives.values.V3
import com.wavesplatform.lang.script.ContractScript
import com.wavesplatform.state.StringDataEntry
import monix.execution.Scheduler

import scala.util.Random
// [WAIT] import com.wavesplatform.lang.{Global, StdLibVersion}
import com.wavesplatform.lang.contract.DApp
import com.wavesplatform.lang.contract.DApp.{VerifierAnnotation, VerifierFunction}
// [WAIT] import com.wavesplatform.lang.v1.compiler.Decompiler
import com.wavesplatform.lang.v1.compiler.Terms._
// [WAIT] import com.wavesplatform.lang.v1.evaluator.ctx.impl.{CryptoContext, PureContext}
import com.wavesplatform.lang.script.v1.ExprScript
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.CommonValidation
import com.wavesplatform.utx.UtxPool
import com.wavesplatform.{NoShrink, TestTime, TestWallet, crypto}
import io.netty.channel.group.ChannelGroup
import org.scalacheck.Gen
import org.scalamock.scalatest.PathMockFactory
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}
import play.api.libs.json._

class AddressRouteSpec
    extends RouteSpec("/addresses")
    with PathMockFactory
    with PropertyChecks
    with RestAPISettingsHelper
    with TestWallet
    with NoShrink {

  private val allAccounts  = testWallet.privateKeyAccounts
  private val allAddresses = allAccounts.map(_.address)
  private val blockchain   = stub[Blockchain]

  private val route =
    AddressApiRoute(restAPISettings, testWallet, blockchain, mock[UtxPool], mock[ChannelGroup], new TestTime)(Scheduler.global).route

  private val generatedMessages = for {
    account <- Gen.oneOf(allAccounts).label("account")
    length  <- Gen.chooseNum(10, 1000)
    message <- Gen.listOfN(length, Gen.alphaNumChar).map(_.mkString).label("message")
  } yield (account, message)

  routePath("/seq/{from}/{to}") in {
    val r1 = Get(routePath("/seq/1/4")) ~> route ~> check {
      val response = responseAs[Seq[String]]
      response.length shouldBe 3
      allAddresses should contain allElementsOf response
      response
    }

    val r2 = Get(routePath("/seq/5/9")) ~> route ~> check {
      val response = responseAs[Seq[String]]
      response.length shouldBe 4
      allAddresses should contain allElementsOf response
      response
    }

    r1 shouldNot contain allElementsOf r2
  }

  routePath("/validate/{address}") in {
    val t = Table(("address", "valid"), allAddresses.map(_ -> true) :+ "invalid-address" -> false: _*)

    forAll(t) { (a, v) =>
      Get(routePath(s"/validate/$a")) ~> route ~> check {
        val r = responseAs[AddressApiRoute.Validity]
        r.address shouldEqual a
        r.valid shouldBe v
      }
    }
  }

  routePath("/seed/{address}") in {
    val account = allAccounts.head
    val path    = routePath(s"/seed/${account.address}")
    Get(path) ~> route should produce(ApiKeyNotValid)
    Get(path) ~> ApiKeyHeader ~> route ~> check {
      val json = responseAs[JsObject]
      (json \ "address").as[String] shouldEqual account.address
      (json \ "seed").as[String] shouldEqual Base58.encode(account.seed)
    }
  }

  private def testSign(path: String, encode: Boolean): Unit =
    forAll(generatedMessages) {
      case (account, message) =>
        val uri = routePath(s"/$path/${account.address}")
        Post(uri, message) ~> route should produce(ApiKeyNotValid)
        Post(uri, message) ~> ApiKeyHeader ~> route ~> check {
          val resp      = responseAs[JsObject]
          val signature = Base58.tryDecodeWithLimit((resp \ "signature").as[String]).get

          (resp \ "message").as[String] shouldEqual (if (encode) Base58.encode(message.getBytes("UTF-8")) else message)
          (resp \ "publicKey").as[String] shouldEqual Base58.encode(account.publicKey)

          crypto.verify(signature, message.getBytes("UTF-8"), account.publicKey) shouldBe true
        }
    }

  routePath("/sign/{address}") in testSign("sign", true)
  routePath("/signText/{address}") in testSign("signText", false)

  private def testVerify(path: String, encode: Boolean): Unit = {

    forAll(generatedMessages.flatMap(m => Gen.oneOf(true, false).map(b => (m, b)))) {
      case ((account, message), b58) =>
        val uri          = routePath(s"/$path/${account.address}")
        val messageBytes = message.getBytes("UTF-8")
        val signature    = crypto.sign(account, messageBytes)
        val validBody = Json.obj(
          "message"   -> JsString(if (encode) if (b58) Base58.encode(messageBytes) else "base64:" ++ Base64.encode(messageBytes) else message),
          "publickey" -> JsString(Base58.encode(account.publicKey)),
          "signature" -> JsString(Base58.encode(signature))
        )

        val emptySignature =
          Json.obj("message" -> JsString(""), "publickey" -> JsString(Base58.encode(account.publicKey)), "signature" -> JsString(""))

        Post(uri, validBody) ~> route should produce(ApiKeyNotValid)
        Post(uri, emptySignature) ~> ApiKeyHeader ~> route ~> check {
          (responseAs[JsObject] \ "valid").as[Boolean] shouldBe false
        }
        Post(uri, validBody) ~> ApiKeyHeader ~> route ~> check {
          (responseAs[JsObject] \ "valid").as[Boolean] shouldBe true
        }
    }
  }

  routePath("/verifyText/{address}") in testVerify("verifyText", false)
  routePath("/verify/{address}") in testVerify("verify", true)

  routePath("") in {
    Post(routePath("")) ~> route should produce(ApiKeyNotValid)
    Post(routePath("")) ~> ApiKeyHeader ~> route ~> check {
      allAddresses should not contain (responseAs[JsObject] \ "address").as[String]
    }
  }

  routePath("/{address}") in {
    Delete(routePath(s"/${allAddresses.head}")) ~> ApiKeyHeader ~> route ~> check {
      (responseAs[JsObject] \ "deleted").as[Boolean] shouldBe true
    }
  }

  routePath(s"/scriptInfo/${allAddresses(1)}") in {
    (blockchain.accountScript _).when(allAccounts(1).toAddress).onCall((_: AddressOrAlias) => Some(ExprScript(TRUE).explicitGet()))
    Get(routePath(s"/scriptInfo/${allAddresses(1)}")) ~> route ~> check {
      val response = responseAs[JsObject]
      (response \ "address").as[String] shouldBe allAddresses(1)
      (response \ "script").as[String] shouldBe "base64:AQa3b8tH"
      (response \ "scriptText").as[String] shouldBe "TRUE" // [WAIT] "true"
      (response \ "complexity").as[Long] shouldBe 1
      (response \ "extraFee").as[Long] shouldBe CommonValidation.ScriptExtraFee
    }

    (blockchain.accountScript _).when(allAccounts(2).toAddress).onCall((_: AddressOrAlias) => None)
    Get(routePath(s"/scriptInfo/${allAddresses(2)}")) ~> route ~> check {
      val response = responseAs[JsObject]
      (response \ "address").as[String] shouldBe allAddresses(2)
      (response \ "script").asOpt[String] shouldBe None
      (response \ "scriptText").asOpt[String] shouldBe None
      (response \ "complexity").as[Long] shouldBe 0
      (response \ "extraFee").as[Long] shouldBe 0
    }

    val contractWithMeta = DApp(
      meta = ByteStr.fromByteArray(Array(1, 2, 3, 4)),
      decs = List(),
      callableFuncs = List(),
      verifierFuncOpt = Some(VerifierFunction(VerifierAnnotation("t"), FUNC("verify", List(), TRUE)))
    )
    (blockchain.accountScript _)
      .when(allAccounts(3).toAddress)
      .onCall((_: AddressOrAlias) => Some(ContractScript(V3, contractWithMeta).explicitGet()))
    Get(routePath(s"/scriptInfo/${allAddresses(3)}")) ~> route ~> check {
      val response = responseAs[JsObject]
      (response \ "address").as[String] shouldBe allAddresses(3)
      // [WAIT] (response \ "script").as[String] shouldBe "base64:AAIDAAAAAAAAAA[QBAgMEAAAAAAAAAAAAAAABAAAAAXQBAAAABnZlcmlmeQAAAAAG65AUYw=="
      (response \ "script").as[String] shouldBe "base64:AAIDAAAAAAAAAAQBAgMEAAAAAAAAAAAAAAABAAAAAXQBAAAABnZlcmlmeQAAAAAG65AUYw=="
      (response \ "scriptText").as[String] shouldBe "DApp(2VfUX,List(),List(),Some(VerifierFunction(VerifierAnnotation(t),FUNC(verify,List(),TRUE))))"
      // [WAIT]                                           Decompiler(
      //      testContract,
      //      Monoid.combineAll(Seq(PureContext.build(com.wavesplatform.lang.directives.values.StdLibVersion.V3), CryptoContext.build(Global))).decompilerContext)
      (response \ "complexity").as[Long] shouldBe 11
      (response \ "extraFee").as[Long] shouldBe CommonValidation.ScriptExtraFee
    }
  }

  routePath(s"/data/${allAddresses(1)}?matches=regex") in {
    val testData: Map[String, String] = (
      (1 to 10).map(i => s"SomeKey#$i") ++
        (1 to 10).map(i => s"OtherKey#$i")
    ).map { k =>
      k -> Random.nextString(16)
    }.toMap

    (blockchain.accountDataKeys _)
      .when(allAccounts(1).toAddress)
      .returning(testData.keys.toList)
      .anyNumberOfTimes()

    testData.foreach {
      case (k, v) =>
        (blockchain
          .accountData(_: Address, _: String))
          .when(allAccounts(1).toAddress, k)
          .returning(Some(StringDataEntry(k, v)))
          .anyNumberOfTimes()
    }

    val regex = """^Some.*$"""

    Get(routePath(s"""/data/${allAddresses(1)}?matches=$regex""")) ~> route ~> check {
      val kvs = responseAs[JsArray].value.map { json =>
        ((json \ "key").as[String], (json \ "value").as[String])
      }.toList

      assert(kvs forall { case (k, v) => k.startsWith("Some") && testData(k) == v })
    }

    val urlEncRegex = """%5ESome.%2A%24"""

    Get(routePath(s"""/data/${allAddresses(1)}?matches=$urlEncRegex""")) ~> route ~> check {
      val kvs = responseAs[JsArray].value.map { json =>
        ((json \ "key").as[String], (json \ "value").as[String])
      }.toList

      assert(kvs forall { case (k, v) => k.startsWith("Some") && testData(k) == v })
    }
  }
}
