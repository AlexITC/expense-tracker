package controllers

import net.wiringbits.actions.subscriptions.*
import net.wiringbits.api.endpoints.SubscriptionsEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.subscriptions.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SubscriptionsController @Inject() (
    getSubscriptionsAction: GetSubscriptionsAction,
    createSubscriptionAction: CreateSubscriptionAction,
    updateSubscriptionAction: UpdateSubscriptionAction,
    deleteSubscriptionAction: DeleteSubscriptionAction,
    changeSubscriptionPriceAction: ChangeSubscriptionPriceAction,
    cancelSubscriptionAction: CancelSubscriptionAction,
    resumeSubscriptionAction: ResumeSubscriptionAction,
    getSubscriptionStartDateSuggestionAction: GetSubscriptionStartDateSuggestionAction,
    addSubscriptionExtraChargeAction: AddSubscriptionExtraChargeAction,
    deleteSubscriptionExtraChargeAction: DeleteSubscriptionExtraChargeAction,
    getSubscriptionReconciliationAction: GetSubscriptionReconciliationAction,
    addSubscriptionManualPaymentAction: AddSubscriptionManualPaymentAction,
    deleteSubscriptionManualPaymentAction: DeleteSubscriptionManualPaymentAction,
    sessionAuthenticator: SessionAuthenticator
)(implicit ec: ExecutionContext) {
  import sessionAuthenticator.authHandler
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getSubscriptions(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetSubscriptions.Response]] =
    handleRequest {
      for {
        userId <- userIdF
        response <- getSubscriptionsAction(userId)
      } yield Right(response)
    }

  private def createSubscription(
      request: CreateSubscription.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateSubscription.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(s"Create subscription: userId=$userId, name=${request.name}")
      response <- createSubscriptionAction(userId, request)
    } yield Right(response)
  }

  private def updateSubscription(
      subscriptionId: UUID,
      request: UpdateSubscription.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateSubscription.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(s"Update subscription: userId=$userId, subscriptionId=$subscriptionId")
      response <- updateSubscriptionAction(subscriptionId = subscriptionId, userId = userId, request)
    } yield Right(response)
  }

  private def deleteSubscription(
      subscriptionId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteSubscription.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(s"Delete subscription: userId=$userId, subscriptionId=$subscriptionId")
      response <- deleteSubscriptionAction(subscriptionId = subscriptionId, userId = userId)
    } yield Right(response)
  }

  private def changeSubscriptionPrice(
      subscriptionId: UUID,
      request: ChangeSubscriptionPrice.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, ChangeSubscriptionPrice.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(
        s"Change subscription price: userId=$userId, subscriptionId=$subscriptionId, effectiveFrom=${request.effectiveFrom}"
      )
      response <- changeSubscriptionPriceAction(subscriptionId = subscriptionId, userId = userId, request)
    } yield Right(response)
  }

  private def cancelSubscription(
      subscriptionId: UUID,
      request: CancelSubscription.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CancelSubscription.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(
        s"Cancel subscription: userId=$userId, subscriptionId=$subscriptionId, effectiveTo=${request.effectiveTo}"
      )
      response <- cancelSubscriptionAction(subscriptionId = subscriptionId, userId = userId, request)
    } yield Right(response)
  }

  private def resumeSubscription(
      subscriptionId: UUID,
      request: ResumeSubscription.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, ResumeSubscription.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(
        s"Resume subscription: userId=$userId, subscriptionId=$subscriptionId, effectiveFrom=${request.effectiveFrom}"
      )
      response <- resumeSubscriptionAction(subscriptionId = subscriptionId, userId = userId, request)
    } yield Right(response)
  }

  private def getStartDateSuggestion(
      merchantId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, GetSubscriptionStartDateSuggestion.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(s"Get subscription start date suggestion: userId=$userId, merchantId=$merchantId")
      response <- getSubscriptionStartDateSuggestionAction(userId = userId, merchantId = merchantId)
    } yield Right(response)
  }

  private def addExtraCharge(
      subscriptionId: UUID,
      request: AddSubscriptionExtraCharge.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, AddSubscriptionExtraCharge.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(
        s"Add subscription extra charge: userId=$userId, subscriptionId=$subscriptionId, recurrence=${request.recurrence}"
      )
      response <- addSubscriptionExtraChargeAction(subscriptionId = subscriptionId, userId = userId, request)
    } yield Right(response)
  }

  private def deleteExtraCharge(
      subscriptionId: UUID,
      extraChargeId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteSubscriptionExtraCharge.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(
        s"Delete subscription extra charge: userId=$userId, subscriptionId=$subscriptionId, extraChargeId=$extraChargeId"
      )
      response <- deleteSubscriptionExtraChargeAction(
        subscriptionId = subscriptionId,
        extraChargeId = extraChargeId,
        userId = userId
      )
    } yield Right(response)
  }

  private def getReconciliation(
      subscriptionId: Option[UUID],
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, GetSubscriptionReconciliation.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(s"Get subscription reconciliation: userId=$userId, subscriptionId=$subscriptionId")
      response <- getSubscriptionReconciliationAction(userId = userId, subscriptionId = subscriptionId)
    } yield Right(response)
  }

  private def addManualPayment(
      subscriptionId: UUID,
      request: AddSubscriptionManualPayment.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, AddSubscriptionManualPayment.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(s"Add subscription manual payment: userId=$userId, subscriptionId=$subscriptionId")
      response <- addSubscriptionManualPaymentAction(subscriptionId = subscriptionId, userId = userId, request)
    } yield Right(response)
  }

  private def deleteManualPayment(
      subscriptionId: UUID,
      manualPaymentId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteSubscriptionManualPayment.Response]] = handleRequest {
    for {
      userId <- userIdF
      _ = logger.info(
        s"Delete subscription manual payment: userId=$userId, subscriptionId=$subscriptionId, manualPaymentId=$manualPaymentId"
      )
      response <- deleteSubscriptionManualPaymentAction(
        subscriptionId = subscriptionId,
        manualPaymentId = manualPaymentId,
        userId = userId
      )
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      SubscriptionsEndpoints.getSubscriptions.serverLogic(getSubscriptions),
      SubscriptionsEndpoints.getReconciliation.serverLogic(getReconciliation),
      SubscriptionsEndpoints.createSubscription.serverLogic(createSubscription),
      SubscriptionsEndpoints.updateSubscription.serverLogic(updateSubscription),
      SubscriptionsEndpoints.deleteSubscription.serverLogic(deleteSubscription),
      SubscriptionsEndpoints.changeSubscriptionPrice.serverLogic(changeSubscriptionPrice),
      SubscriptionsEndpoints.cancelSubscription.serverLogic(cancelSubscription),
      SubscriptionsEndpoints.resumeSubscription.serverLogic(resumeSubscription),
      SubscriptionsEndpoints.getStartDateSuggestion.serverLogic(getStartDateSuggestion),
      SubscriptionsEndpoints.addExtraCharge.serverLogic(addExtraCharge),
      SubscriptionsEndpoints.deleteExtraCharge.serverLogic(deleteExtraCharge),
      SubscriptionsEndpoints.addManualPayment.serverLogic(addManualPayment),
      SubscriptionsEndpoints.deleteManualPayment.serverLogic(deleteManualPayment)
    )
  }
}
