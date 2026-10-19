package net.wiringbits.apis

import scala.concurrent.Future

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

case class JevChoice(choice: String, confidence: BigDecimal, probabilities: Map[String, BigDecimal])

/** JEV kept answering 429 (Too Many Requests) after the client's retries; the caller may retry later. */
class JevRateLimitedException(message: String) extends RuntimeException(message)
