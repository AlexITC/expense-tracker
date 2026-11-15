package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.DeleteSubscription
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteSubscriptionAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(subscriptionId: UUID, userId: UUID): Future[DeleteSubscription.Response] = {
    subscriptionsRepository
      .delete(id = subscriptionId, userId = userId)
      .map(_ => DeleteSubscription.Response())
  }
}
