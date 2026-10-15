package controllers

import net.wiringbits.actions.importrules.*
import net.wiringbits.api.endpoints.ImportRulesEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.merchants.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ImportRulesController @Inject() (
    getImportRulesAction: GetImportRulesAction,
    createImportRuleAction: CreateImportRuleAction,
    updateImportRuleAction: UpdateImportRuleAction,
    deleteImportRuleAction: DeleteImportRuleAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetImportRules.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getImportRulesAction(userId)
    } yield Right(response)
  }

  private def create(
      request: CreateImportRule.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateImportRule.Response]] = handleRequest {
    logger.info(s"Create import rule: action=${request.action.entryName} pattern=${request.pattern}")
    for {
      userId <- userIdF
      response <- createImportRuleAction(userId, request)
    } yield Right(response)
  }

  private def update(
      ruleId: UUID,
      request: UpdateImportRule.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateImportRule.Response]] = handleRequest {
    logger.info(s"Update import rule: $ruleId")
    for {
      userId <- userIdF
      response <- updateImportRuleAction(ruleId, userId, request)
    } yield Right(response)
  }

  private def delete(
      ruleId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteImportRule.Response]] = handleRequest {
    logger.info(s"Delete import rule: $ruleId")
    for {
      userId <- userIdF
      response <- deleteImportRuleAction(ruleId, userId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] =
    List(
      ImportRulesEndpoints.getAll.serverLogic(getAll),
      ImportRulesEndpoints.create.serverLogic(create),
      ImportRulesEndpoints.update.serverLogic(update),
      ImportRulesEndpoints.delete.serverLogic(delete)
    )
}
