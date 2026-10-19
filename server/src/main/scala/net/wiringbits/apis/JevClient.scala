package net.wiringbits.apis

import net.wiringbits.config.JevConfig
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.JsonBodyWritables.*
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Client for JEV's `systemone` endpoint, asking a single "choice" question at a time. */
trait JevClient {

  /** Asks JEV to pick one of the `criteria` keys (key -> description) for the given `state`. */
  def choose(
      state: String,
      questionName: String,
      instructions: String,
      criteria: Map[String, String]
  ): Future[JevChoice]
}

object JevClient {

  /** Criteria key callers offer when none of the listed merchants fits; the mock picks it whenever it's offered. */
  val NoMerchant = "none"

  /** Calls JEV's `POST /v1/systemone` with a single "choice" question.
    *
    * Rate limits: JEV has its own request limit (unlike Nova Lite's, see `StatementSuggestionsService`), but it can
    * also answer 429. We deliberately don't add an app-level rate limiter: a light backoff (see `RetryDelays`) plus the
    * background job's retry are enough for now. If JEV throttling shows up, the planned next step is a Postgres
    * single-row "spacing" rate limiter (one row per resource, an atomic upsert bumping `next_available_at`); it's not
    * implemented yet.
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
        def unexpected(reason: String) =
          s"Unexpected JEV response for question '$questionName' ($reason), status ${response.status}: ${response.body}"
        Try(response.json) match {
          case Failure(cause) => Future.failed(new RuntimeException(unexpected("the body is not JSON"), cause))
          case Success(json) =>
            parse(json, questionName, criteria) match {
              case Right(choice) => Future.successful(choice)
              case Left(reason) => Future.failed(new RuntimeException(unexpected(reason)))
            }
        }
      }
    }

    private def send(body: JsValue, retryDelays: List[FiniteDuration]): Future[WSResponse] =
      ws.url(s"${config.baseUrl}/v1/systemone")
        .addHttpHeaders("Authorization" -> s"Bearer ${config.apiKey.string}")
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
            case status =>
              Future.failed(new RuntimeException(s"JEV request failed, status $status: ${response.body}"))
          }
        }

    /** The answer to `questionName`, or why it couldn't be read. */
    private def parse(json: JsValue, questionName: String, criteria: Map[String, String]): Either[String, JevChoice] = {
      val answer = json \ "answers" \ questionName
      for {
        _ <- (answer \ "type").asOpt[String].filter(_ == "choice").toRight("the answer type is not 'choice'")
        choice <- (answer \ "choice").asOpt[String].toRight("missing string 'choice'")
        _ <- Either.cond(criteria.contains(choice), (), s"choice '$choice' is not one of the criteria keys")
        confidence <- (answer \ "confidence").asOpt[BigDecimal].toRight("missing numeric 'confidence'")
        probabilities <- (answer \ "probabilities")
          .asOpt[Map[String, BigDecimal]]
          .toRight("missing 'probabilities' object")
      } yield JevChoice(choice, confidence, probabilities)
    }
  }

  /** Deterministic, stateless stand-in for JEV: picks [[NoMerchant]] when offered, otherwise the smallest criteria key,
    * with confidence 0 and no probabilities. Mock-mode suggestions are therefore placeholder picks, not real
    * predictions.
    */
  class MockJevClient extends JevClient {
    override def choose(
        state: String,
        questionName: String,
        instructions: String,
        criteria: Map[String, String]
    ): Future[JevChoice] = {
      val picked = if (criteria.contains(NoMerchant)) Some(NoMerchant) else criteria.keys.minOption
      picked match {
        case Some(choice) => Future.successful(JevChoice(choice, BigDecimal(0), Map.empty))
        case None => Future.failed(new RuntimeException(s"No criteria to choose from for question '$questionName'"))
      }
    }
  }
}

case class JevChoice(choice: String, confidence: BigDecimal, probabilities: Map[String, BigDecimal])

/** JEV kept answering 429 (Too Many Requests) after the client's retries; the caller may retry later. */
class JevRateLimitedException(message: String) extends RuntimeException(message)
