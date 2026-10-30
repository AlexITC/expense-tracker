package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.expenses.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.Future

object ExpensesEndpoints {
  private val baseEndpoint = endpoint
    .in("expenses")
    .tag("Expenses")
    .errorOut(errorResponseErrorOut)

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (Option[LocalDate], Option[LocalDate], Boolean, Future[UUID]),
    ErrorResponse,
    GetExpenses.Response,
    Any
  ] =
    baseEndpoint.get
      .in(query[Option[LocalDate]]("from"))
      .in(query[Option[LocalDate]]("to"))
      .in(query[Boolean]("unlinkedOnly").default(false))
      .in(userAuth)
      .out(jsonBody[GetExpenses.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all expenses for the authenticated user")

  def create(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (CreateExpense.Request, Future[UUID]), ErrorResponse, CreateExpense.Response, Any] =
    baseEndpoint.post
      .in(jsonBody[CreateExpense.Request])
      .in(userAuth)
      .out(jsonBody[CreateExpense.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create an expense for the authenticated user")

  def update(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UpdateExpense.Request, Future[UUID]), ErrorResponse, UpdateExpense.Response, Any] =
    baseEndpoint.put
      .in(path[UUID]("expenseId"))
      .in(jsonBody[UpdateExpense.Request])
      .in(userAuth)
      .out(jsonBody[UpdateExpense.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Update an expense")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteExpense.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("expenseId"))
      .in(userAuth)
      .out(jsonBody[DeleteExpense.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete an expense")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(getAll, create, update, delete)
}
