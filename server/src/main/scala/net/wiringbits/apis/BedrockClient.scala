package net.wiringbits.apis

import scala.concurrent.Future

trait BedrockClient {

  /** Extract a bank statement's transactions + metadata directly from its native text, as a JSON string (the caller
    * parses and verifies it). Uses the configured statement model (Sonnet by default). `feedback`, when set, is a
    * correction hint from a previous attempt that didn't reconcile, so the model can try again.
    */
  def extractBankStatement(statementText: String, feedback: Option[String] = None): Future[String]

  /** Run a short classification prompt on the configured suggestions model and return the model's raw text (the caller
    * parses and validates it). Only used by the unwired Nova `StatementSuggestionsService`; the active suggestions
    * engine is `JevStatementSuggestionsService`.
    */
  def suggest(prompt: String): Future[String]
}
