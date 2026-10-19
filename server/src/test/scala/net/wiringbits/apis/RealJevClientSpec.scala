package net.wiringbits.apis

import net.wiringbits.config.JevConfig
import net.wiringbits.models.JevApiKey
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.*
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.{BodyWritable, WSClient, WSRequest, WSResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RealJevClientSpec extends AnyWordSpec with MockitoSugar with ScalaFutures with BeforeAndAfterAll {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  private val actorSystem = ActorSystem("RealJevClientSpec")
  private val config = JevConfig(JevApiKey("secret-key"), "https://jev.test", "jev-latest", JevConfig.Real)
  private val criteria = Map("a" -> "Groceries", "b" -> "Transport")

  override def afterAll(): Unit = {
    val _ = actorSystem.terminate().futureValue
  }

  private def answer(choice: String): JsValue = Json.obj(
    "answers" -> Json.obj(
      "category" -> Json.obj(
        "type" -> "choice",
        "choice" -> choice,
        "confidence" -> 0.8,
        "probabilities" -> Json.obj("a" -> 0.8, "b" -> 0.2)
      )
    ),
    "usage" -> Json.obj("input_tokens" -> 42)
  )

  private def answerWith(f: JsObject => JsObject): JsValue = {
    val base = Json.obj("type" -> "choice", "choice" -> "a", "confidence" -> 0.8, "probabilities" -> Json.obj("a" -> 1))
    Json.obj("answers" -> Json.obj("category" -> f(base)))
  }

  private def response(status: Int, json: JsValue): WSResponse = {
    val res = mock[WSResponse]
    when(res.status).thenReturn(status)
    when(res.json).thenReturn(json)
    when(res.body).thenReturn(json.toString)
    res
  }

  private def setup(responses: WSResponse*): (RealJevClient, WSRequest) = {
    val ws = mock[WSClient]
    val request = mock[WSRequest]
    when(ws.url(anyString())).thenReturn(request)
    when(request.addHttpHeaders(any[(String, String)]())).thenReturn(request)
    val futures = responses.map(Future.successful)
    val _ = when(request.post[JsValue](any[JsValue]())(using any[BodyWritable[JsValue]]()))
      .thenReturn(futures.head, futures.tail*)
    (new RealJevClient(config, ws, actorSystem), request)
  }

  private def choose(client: RealJevClient) =
    client.choose("Row: CORNER SHOP -12.50", "category", "Pick the category", criteria)

  "choose" should {
    "send the expected JSON body and Bearer header" in {
      val (client, request) = setup(response(200, answer("a")))
      choose(client).futureValue

      val bodyCaptor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(request).post[JsValue](bodyCaptor.capture())(using any[BodyWritable[JsValue]]())
      bodyCaptor.getValue must be(
        Json.obj(
          "model" -> "jev-latest",
          "state" -> "Row: CORNER SHOP -12.50",
          "questions" -> Json.obj(
            "category" -> Json.obj(
              "type" -> "choice",
              "instructions" -> "Pick the category",
              "criteria" -> Json.obj("a" -> "Groceries", "b" -> "Transport")
            )
          )
        )
      )
      val _ = verify(request).addHttpHeaders(
        "Authorization" -> "Bearer secret-key",
        "Content-Type" -> "application/json"
      )
    }

    "parse the choice, confidence and probabilities" in {
      val (client, _) = setup(response(200, answer("b")))
      choose(client).futureValue must be(
        JevChoice("b", BigDecimal("0.8"), Map("a" -> BigDecimal("0.8"), "b" -> BigDecimal("0.2")))
      )
    }

    "fail on a non-2xx response including the status" in {
      val (client, _) = setup(response(500, Json.obj("error" -> "boom")))
      val error = choose(client).failed.futureValue
      error.getMessage must include("500")
      error.getMessage must include("boom")
      error.getMessage must not include "secret-key"
    }

    "fail on an unparseable body" in {
      val res = mock[WSResponse]
      when(res.status).thenReturn(200)
      when(res.json).thenThrow(new RuntimeException("invalid json"))
      when(res.body).thenReturn("not json")
      val (client, _) = setup(res)
      val error = choose(client).failed.futureValue
      error.getMessage must include("the body is not JSON")
      error.getMessage must include("not json")
      error.getCause.getMessage must be("invalid json")
    }

    "fail when the answer is missing" in {
      val (client, _) = setup(response(200, Json.obj("answers" -> Json.obj())))
      choose(client).failed.futureValue.getMessage must include("the answer type is not 'choice'")
    }

    "fail when the answer type is not choice" in {
      val (client, _) = setup(response(200, answerWith(_ + ("type" -> Json.toJson("text")))))
      choose(client).failed.futureValue.getMessage must include("the answer type is not 'choice'")
    }

    "fail when the confidence is missing" in {
      val (client, _) = setup(response(200, answerWith(_ - "confidence")))
      choose(client).failed.futureValue.getMessage must include("missing numeric 'confidence'")
    }

    "fail when the probabilities are missing" in {
      val (client, _) = setup(response(200, answerWith(_ - "probabilities")))
      choose(client).failed.futureValue.getMessage must include("missing 'probabilities' object")
    }

    "fail when the choice is not one of the criteria" in {
      val (client, _) = setup(response(200, answer("zzz")))
      choose(client).failed.futureValue.getMessage must include("choice 'zzz' is not one of the criteria keys")
    }

    "retry after a 429 and succeed" in {
      val (client, request) = setup(response(429, Json.obj()), response(200, answer("a")))
      choose(client).futureValue.choice must be("a")
      val _ = verify(request, times(2)).post[JsValue](any[JsValue]())(using any[BodyWritable[JsValue]]())
    }

    "fail with JevRateLimitedException when 429 persists" in {
      val (client, request) = setup(response(429, Json.obj("error" -> "slow down")))
      choose(client).failed.futureValue mustBe a[JevRateLimitedException]
      val _ = verify(request, times(3)).post[JsValue](any[JsValue]())(using any[BodyWritable[JsValue]]())
    }
  }
}
