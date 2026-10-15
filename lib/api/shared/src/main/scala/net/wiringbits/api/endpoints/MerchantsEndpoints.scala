package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.merchants.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object MerchantsEndpoints {
  private val baseEndpoint = endpoint
    .in("merchants")
    .tag("Merchants")
    .errorOut(errorResponseErrorOut)

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetMerchants.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetMerchants.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all merchants for the authenticated user")

  def create(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (CreateMerchant.Request, Future[UUID]), ErrorResponse, CreateMerchant.Response, Any] =
    baseEndpoint.post
      .in(jsonBody[CreateMerchant.Request])
      .in(userAuth)
      .out(jsonBody[CreateMerchant.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create a merchant for the authenticated user")

  def update(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UpdateMerchant.Request, Future[UUID]), ErrorResponse, UpdateMerchant.Response, Any] =
    baseEndpoint.put
      .in(path[UUID]("merchantId"))
      .in(jsonBody[UpdateMerchant.Request])
      .in(userAuth)
      .out(jsonBody[UpdateMerchant.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Update a merchant's name and notes")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteMerchant.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("merchantId"))
      .in(userAuth)
      .out(jsonBody[DeleteMerchant.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a merchant")

  def getPending(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetPendingMerchants.Response, Any] =
    baseEndpoint.get
      .in("pending")
      .in(userAuth)
      .out(jsonBody[GetPendingMerchants.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all pending (unreviewed) merchants for the authenticated user")

  def review(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, ReviewMerchant.Request, Future[UUID]), ErrorResponse, ReviewMerchant.Response, Any] =
    baseEndpoint.put
      .in(path[UUID]("merchantId"))
      .in("review")
      .in(jsonBody[ReviewMerchant.Request])
      .in(userAuth)
      .out(jsonBody[ReviewMerchant.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Mark a pending merchant as reviewed (optionally updating name and category)")

  def markAsDuplicate(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, MarkMerchantAsDuplicate.Request, Future[UUID]),
    ErrorResponse,
    MarkMerchantAsDuplicate.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("merchantId"))
      .in("duplicate")
      .in(jsonBody[MarkMerchantAsDuplicate.Request])
      .in(userAuth)
      .out(jsonBody[MarkMerchantAsDuplicate.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary(
        "Mark a pending merchant as a duplicate of an existing merchant, creating a rule and re-pointing all references"
      )

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(getAll, create, update, delete, getPending, review, markAsDuplicate)
}
