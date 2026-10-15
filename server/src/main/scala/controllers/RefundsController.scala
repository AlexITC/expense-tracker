package controllers

import net.wiringbits.actions.refunds.*
import net.wiringbits.api.endpoints.RefundsEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.refunds.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class RefundsController @Inject() (
    getRefundsAction: GetRefundsAction,
    createRefundAction: CreateRefundAction,
    updateRefundAction: UpdateRefundAction,
    deleteRefundAction: DeleteRefundAction,
    linkRefundToExpenseAction: LinkRefundToExpenseAction,
    unlinkRefundFromExpenseAction: UnlinkRefundFromExpenseAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetRefunds.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getRefundsAction(userId)
    } yield Right(response)
  }

  private def create(
      request: CreateRefund.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateRefund.Response]] = handleRequest {
    logger.info(s"Create refund: ${request.amount} on ${request.date}")
    for {
      userId <- userIdF
      response <- createRefundAction(userId, request)
    } yield Right(response)
  }

  private def update(
      refundId: UUID,
      request: UpdateRefund.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateRefund.Response]] = handleRequest {
    logger.info(s"Update refund: $refundId")
    for {
      userId <- userIdF
      response <- updateRefundAction(refundId, userId, request)
    } yield Right(response)
  }

  private def delete(
      refundId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteRefund.Response]] = handleRequest {
    logger.info(s"Delete refund: $refundId")
    for {
      userId <- userIdF
      response <- deleteRefundAction(refundId, userId)
    } yield Right(response)
  }

  private def linkToExpense(
      refundId: UUID,
      request: LinkRefundToExpense.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, LinkRefundToExpense.Response]] = handleRequest {
    logger.info(s"Link refund to expense: refund=$refundId expense=${request.expenseId}")
    for {
      userId <- userIdF
      response <- linkRefundToExpenseAction(refundId, userId, request)
    } yield Right(response)
  }

  private def unlinkFromExpense(
      refundId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UnlinkRefundFromExpense.Response]] = handleRequest {
    logger.info(s"Unlink refund from expense: $refundId")
    for {
      userId <- userIdF
      response <- unlinkRefundFromExpenseAction(refundId, userId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] =
    List(
      RefundsEndpoints.getAll.serverLogic(getAll),
      RefundsEndpoints.create.serverLogic(create),
      RefundsEndpoints.update.serverLogic(update),
      RefundsEndpoints.delete.serverLogic(delete),
      RefundsEndpoints.linkToExpense.serverLogic(linkToExpense),
      RefundsEndpoints.unlinkFromExpense.serverLogic(unlinkFromExpense)
    )
}
