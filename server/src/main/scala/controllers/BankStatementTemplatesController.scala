package controllers

import net.wiringbits.actions.bankstatementtemplates.*
import net.wiringbits.api.endpoints.BankStatementTemplatesEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.bankstatementtemplates.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class BankStatementTemplatesController @Inject() (
    aiExtractBankStatementAction: AiExtractBankStatementAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def aiExtract(
      request: AiExtractBankStatement.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, AiExtractBankStatement.Response]] = handleRequest {
    logger.info(s"AI-extract bank statement: ${request.bankStatementId}")
    for {
      userId <- userIdF
      response <- aiExtractBankStatementAction(userId, request)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] =
    List(
      BankStatementTemplatesEndpoints.aiExtract.serverLogic(aiExtract)
    )
}
