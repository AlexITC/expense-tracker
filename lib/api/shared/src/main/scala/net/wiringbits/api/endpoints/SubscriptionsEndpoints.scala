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

  // Declared before any `/subscriptions/{subscriptionId}` GET so the literal path segment always wins.
  def getReconciliation(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (Option[UUID], Future[UUID]), ErrorResponse, GetSubscriptionReconciliation.Response, Any] =
    baseEndpoint.get
      .in("reconciliation")
      .in(query[Option[UUID]]("subscriptionId"))
      .in(userAuth)
      .out(jsonBody[GetSubscriptionReconciliation.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Reconcile the expected charges of the subscriptions with the recorded payments")

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

  def getStartDateSuggestion(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, GetSubscriptionStartDateSuggestion.Response, Any] =
    baseEndpoint.get
      .in("start-date-suggestion")
      .in(path[UUID]("merchantId"))
      .in(userAuth)
      .out(jsonBody[GetSubscriptionStartDateSuggestion.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Suggest a subscription start date from the merchant's earliest expense")

  def addExtraCharge(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, AddSubscriptionExtraCharge.Request, Future[UUID]),
    ErrorResponse,
    AddSubscriptionExtraCharge.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("subscriptionId"))
      .in("extra-charges")
      .in(jsonBody[AddSubscriptionExtraCharge.Request])
      .in(userAuth)
      .out(jsonBody[AddSubscriptionExtraCharge.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Add an extra charge to a subscription")

  def deleteExtraCharge(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, UUID, Future[UUID]),
    ErrorResponse,
    DeleteSubscriptionExtraCharge.Response,
    Any
  ] =
    baseEndpoint.delete
      .in(path[UUID]("subscriptionId"))
      .in("extra-charges")
      .in(path[UUID]("extraChargeId"))
      .in(userAuth)
      .out(jsonBody[DeleteSubscriptionExtraCharge.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete an extra charge of a subscription")

  def addManualPayment(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, AddSubscriptionManualPayment.Request, Future[UUID]),
    ErrorResponse,
    AddSubscriptionManualPayment.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("subscriptionId"))
      .in("manual-payments")
      .in(jsonBody[AddSubscriptionManualPayment.Request])
      .in(userAuth)
      .out(jsonBody[AddSubscriptionManualPayment.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Record a manual payment for a subscription")

  def deleteManualPayment(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, UUID, Future[UUID]),
    ErrorResponse,
    DeleteSubscriptionManualPayment.Response,
    Any
  ] =
    baseEndpoint.delete
      .in(path[UUID]("subscriptionId"))
      .in("manual-payments")
      .in(path[UUID]("manualPaymentId"))
      .in(userAuth)
      .out(jsonBody[DeleteSubscriptionManualPayment.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a manual payment of a subscription")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(
      getSubscriptions,
      getReconciliation,
      createSubscription,
      updateSubscription,
      deleteSubscription,
      changeSubscriptionPrice,
      cancelSubscription,
      resumeSubscription,
      getStartDateSuggestion,
      addExtraCharge,
      deleteExtraCharge,
      addManualPayment,
      deleteManualPayment
    )
}
