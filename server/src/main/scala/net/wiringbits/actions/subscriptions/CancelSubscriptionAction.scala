package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.CancelSubscription
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CancelSubscriptionAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(
      subscriptionId: UUID,
      userId: UUID,
      request: CancelSubscription.Request
  ): Future[CancelSubscription.Response] = {
    subscriptionsRepository
      .cancel(id = subscriptionId, userId = userId, effectiveTo = request.effectiveTo)
      .map(_ => CancelSubscription.Response())
  }
}
