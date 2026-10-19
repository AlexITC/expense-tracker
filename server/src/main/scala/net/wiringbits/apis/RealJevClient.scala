package net.wiringbits.apis

import net.wiringbits.config.JevConfig
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.JsonBodyWritables.*
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Calls JEV's `POST /v1/systemone` with a single "choice" question.
  *
  * Rate limits: the previous engine (Amazon Nova Lite via Bedrock) kept hitting the 20 req/min "Cross-region model
  * inference requests per minute for Amazon Nova Lite" quota. JEV has its own, separate and more generous request
  * limit, but it can also answer 429. We deliberately don't add an app-level rate limiter: the light backoff here (at
  * most 2 retries, ~1s then ~2s) plus the background job's retry are enough for now. If JEV throttling shows up, the
  * planned next step is a Postgres single-row "spacing" rate limiter (one row per resource, an atomic upsert bumping
  * `next_available_at`); it's not implemented yet.
  */
class RealJevClient(config: JevConfig, ws: WSClient, actorSystem: ActorSystem)(implicit ec: ExecutionContext)
    extends JevClient {

  private val RetryDelays = List(1.second, 2.seconds)

  override def choose(
      state: String,
      questionName: String,
      instructions: String,
      criteria: Map[String, String]
  ): Future[JevChoice] = {
    val body = Json.obj(
      "model" -> config.model,
      "state" -> state,
      "questions" -> Json.obj(
        questionName -> Json.obj("type" -> "choice", "instructions" -> instructions, "criteria" -> criteria)
      )
    )
    send(body, RetryDelays).flatMap { response =>
      parse(response, questionName, criteria) match {
        case Right(choice) => Future.successful(choice)
        case Left(fail) =>
          val message =
            s"Unexpected JEV response for question '$questionName' (${fail.reason}), status ${response.status}: ${response.body}"
          Future.failed(fail match {
            case RealJevClient.ParseFail.NotJson(cause) => new RuntimeException(message, cause)
            case _ => new RuntimeException(message)
          })
      }
    }
  }

  private def send(body: JsValue, retryDelays: List[FiniteDuration]): Future[WSResponse] =
    ws.url(s"${config.baseUrl}/v1/systemone")
      .addHttpHeaders("Authorization" -> s"Bearer ${config.apiKey.string}", "Content-Type" -> "application/json")
      .post(body)
      .flatMap { response =>
        response.status match {
          case status if status >= 200 && status < 300 => Future.successful(response)
          case 429 =>
            retryDelays match {
              case delay :: rest => after(delay, actorSystem.scheduler)(send(body, rest))
              case Nil =>
                Future.failed(
                  new JevRateLimitedException(
                    s"JEV is rate limiting us, status 429 after ${RetryDelays.size} retries: ${response.body}"
                  )
                )
            }
          case status => Future.failed(new RuntimeException(s"JEV request failed, status $status: ${response.body}"))
        }
      }

  private def parse(
      response: WSResponse,
      questionName: String,
      criteria: Map[String, String]
  ): Either[RealJevClient.ParseFail, JevChoice] = {
    import RealJevClient.ParseFail
    for {
      json <- Try(response.json).toEither.left.map(ParseFail.NotJson(_))
      answer = json \ "answers" \ questionName
      _ <- (answer \ "type").asOpt[String].filter(_ == "choice").toRight(ParseFail.WrongType)
      choice <- (answer \ "choice").asOpt[String].toRight(ParseFail.MissingChoice)
      _ <- Either.cond(criteria.contains(choice), (), ParseFail.ChoiceNotInCriteria(choice))
      confidence <- (answer \ "confidence").asOpt[BigDecimal].toRight(ParseFail.MissingConfidence)
      probabilities <- (answer \ "probabilities")
        .asOpt[Map[String, BigDecimal]]
        .toRight(ParseFail.MissingProbabilities)
    } yield JevChoice(choice, confidence, probabilities)
  }
}

object RealJevClient {

  /** Why a 2xx JEV response couldn't be turned into a [[JevChoice]]. */
  enum ParseFail {
    case NotJson(cause: Throwable)
    case WrongType
    case MissingChoice
    case ChoiceNotInCriteria(choice: String)
    case MissingConfidence
    case MissingProbabilities

    def reason: String = this match {
      case NotJson(_) => "the body is not JSON"
      case WrongType => "the answer type is not 'choice'"
      case MissingChoice => "missing string 'choice'"
      case ChoiceNotInCriteria(choice) => s"choice '$choice' is not one of the criteria keys"
      case MissingConfidence => "missing numeric 'confidence'"
      case MissingProbabilities => "missing 'probabilities' object"
    }
  }
}
