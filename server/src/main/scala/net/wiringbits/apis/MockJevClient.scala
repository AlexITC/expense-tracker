package net.wiringbits.apis

import scala.concurrent.Future

/** Deterministic, stateless stand-in for JEV: picks `"none"` when offered, otherwise the smallest criteria key, with
  * confidence 0 and no probabilities. Mock-mode suggestions are therefore placeholder picks, not real predictions.
  */
class MockJevClient extends JevClient {
  override def choose(
      state: String,
      questionName: String,
      instructions: String,
      criteria: Map[String, String]
  ): Future[JevChoice] = {
    val picked = if (criteria.contains("none")) Some("none") else criteria.keys.minOption
    picked match {
      case Some(choice) => Future.successful(JevChoice(choice, BigDecimal(0), Map.empty))
      case None => Future.failed(new RuntimeException(s"No criteria to choose from for question '$questionName'"))
    }
  }
}
