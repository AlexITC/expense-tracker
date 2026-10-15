package controllers

import net.wiringbits.actions.expenses.*
import net.wiringbits.api.endpoints.ExpensesEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.expenses.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ExpensesController @Inject() (
    getExpensesAction: GetExpensesAction,
    createExpenseAction: CreateExpenseAction,
    updateExpenseAction: UpdateExpenseAction,
    deleteExpenseAction: DeleteExpenseAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetExpenses.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getExpensesAction(userId)
    } yield Right(response)
  }

  private def create(
      request: CreateExpense.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateExpense.Response]] = handleRequest {
    logger.info(s"Create expense: ${request.amount} on ${request.date}")
    for {
      userId <- userIdF
      response <- createExpenseAction(userId, request)
    } yield Right(response)
  }

  private def update(
      expenseId: UUID,
      request: UpdateExpense.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateExpense.Response]] = handleRequest {
    logger.info(s"Update expense: $expenseId")
    for {
      userId <- userIdF
      response <- updateExpenseAction(expenseId, userId, request)
    } yield Right(response)
  }

  private def delete(
      expenseId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteExpense.Response]] = handleRequest {
    logger.info(s"Delete expense: $expenseId")
    for {
      userId <- userIdF
      response <- deleteExpenseAction(expenseId, userId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      ExpensesEndpoints.getAll.serverLogic(getAll),
      ExpensesEndpoints.create.serverLogic(create),
      ExpensesEndpoints.update.serverLogic(update),
      ExpensesEndpoints.delete.serverLogic(delete)
    )
  }
}
