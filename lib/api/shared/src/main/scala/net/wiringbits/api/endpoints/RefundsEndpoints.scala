package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.refunds.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object RefundsEndpoints {
  private val baseEndpoint = endpoint
    .in("refunds")
    .tag("Refunds")
    .errorOut(errorResponseErrorOut)

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetRefunds.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetRefunds.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all refunds for the authenticated user")

  def create(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (CreateRefund.Request, Future[UUID]), ErrorResponse, CreateRefund.Response, Any] =
    baseEndpoint.post
      .in(jsonBody[CreateRefund.Request])
      .in(userAuth)
      .out(jsonBody[CreateRefund.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create a refund for the authenticated user")

  def update(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UpdateRefund.Request, Future[UUID]), ErrorResponse, UpdateRefund.Response, Any] =
    baseEndpoint.put
      .in(path[UUID]("refundId"))
      .in(jsonBody[UpdateRefund.Request])
      .in(userAuth)
      .out(jsonBody[UpdateRefund.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Update a refund")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteRefund.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("refundId"))
      .in(userAuth)
      .out(jsonBody[DeleteRefund.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a refund")

  def linkToExpense(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, LinkRefundToExpense.Request, Future[UUID]),
    ErrorResponse,
    LinkRefundToExpense.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("refundId") / "link-expense")
      .in(jsonBody[LinkRefundToExpense.Request])
      .in(userAuth)
      .out(jsonBody[LinkRefundToExpense.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Link a refund to an existing expense")

  def unlinkFromExpense(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, UnlinkRefundFromExpense.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("refundId") / "link-expense")
      .in(userAuth)
      .out(jsonBody[UnlinkRefundFromExpense.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Unlink a refund from its expense")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(getAll, create, update, delete, linkToExpense, unlinkFromExpense)
}
