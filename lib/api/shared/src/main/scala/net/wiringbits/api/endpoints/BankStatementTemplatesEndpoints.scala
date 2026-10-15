package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.bankstatementtemplates.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object BankStatementTemplatesEndpoints {
  private val baseEndpoint = endpoint
    .in("bank-statement-templates")
    .tag("BankStatementTemplates")
    .errorOut(errorResponseErrorOut)

  def aiExtract(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (AiExtractBankStatement.Request, Future[UUID]),
    ErrorResponse,
    AiExtractBankStatement.Response,
    Any
  ] =
    baseEndpoint.post
      .in("ai-extract")
      .in(jsonBody[AiExtractBankStatement.Request])
      .in(userAuth)
      .out(jsonBody[AiExtractBankStatement.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Extract a bank statement directly with AI (Sonnet) and verify it against the statement's arithmetic")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(aiExtract)
}
