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
    deleteSubscriptionAction: DeleteSubscriptionAction
)(implicit ec: ExecutionContext) {
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

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      SubscriptionsEndpoints.getSubscriptions.serverLogic(getSubscriptions),
      SubscriptionsEndpoints.createSubscription.serverLogic(createSubscription),
      SubscriptionsEndpoints.updateSubscription.serverLogic(updateSubscription),
      SubscriptionsEndpoints.deleteSubscription.serverLogic(deleteSubscription)
    )
  }
}
