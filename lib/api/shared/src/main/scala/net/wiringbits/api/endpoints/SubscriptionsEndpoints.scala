package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.subscriptions.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object SubscriptionsEndpoints {
  private val baseEndpoint = endpoint
    .in("subscriptions")
    .tag("Subscriptions")
    .errorOut(errorResponseErrorOut)

  def getSubscriptions(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetSubscriptions.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetSubscriptions.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("List all subscriptions")

  def createSubscription(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (CreateSubscription.Request, Future[UUID]), ErrorResponse, CreateSubscription.Response, Any] =
    baseEndpoint.post
      .in(jsonBody[CreateSubscription.Request])
      .in(userAuth)
      .out(jsonBody[CreateSubscription.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create a subscription")

  def updateSubscription(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, UpdateSubscription.Request, Future[UUID]),
    ErrorResponse,
    UpdateSubscription.Response,
    Any
  ] =
    baseEndpoint.put
      .in(path[UUID]("subscriptionId"))
      .in(jsonBody[UpdateSubscription.Request])
      .in(userAuth)
      .out(jsonBody[UpdateSubscription.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Update a subscription")

  def deleteSubscription(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteSubscription.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("subscriptionId"))
      .in(userAuth)
      .out(jsonBody[DeleteSubscription.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a subscription")

  def changeSubscriptionPrice(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, ChangeSubscriptionPrice.Request, Future[UUID]),
    ErrorResponse,
    ChangeSubscriptionPrice.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("subscriptionId"))
      .in("price-change")
      .in(jsonBody[ChangeSubscriptionPrice.Request])
      .in(userAuth)
      .out(jsonBody[ChangeSubscriptionPrice.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Change a subscription's price from a date")

  def cancelSubscription(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, CancelSubscription.Request, Future[UUID]),
    ErrorResponse,
    CancelSubscription.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("subscriptionId"))
      .in("cancel")
      .in(jsonBody[CancelSubscription.Request])
      .in(userAuth)
      .out(jsonBody[CancelSubscription.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Cancel a subscription from a date")

  def resumeSubscription(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, ResumeSubscription.Request, Future[UUID]),
    ErrorResponse,
    ResumeSubscription.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("subscriptionId"))
      .in("resume")
      .in(jsonBody[ResumeSubscription.Request])
      .in(userAuth)
      .out(jsonBody[ResumeSubscription.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Resume a cancelled subscription from a date")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(
      getSubscriptions,
      createSubscription,
      updateSubscription,
      deleteSubscription,
      changeSubscriptionPrice,
      cancelSubscription,
      resumeSubscription
    )
}
