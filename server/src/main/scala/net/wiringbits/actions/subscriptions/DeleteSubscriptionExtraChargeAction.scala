package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.DeleteSubscriptionExtraCharge
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteSubscriptionExtraChargeAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(subscriptionId: UUID, extraChargeId: UUID, userId: UUID): Future[DeleteSubscriptionExtraCharge.Response] = {
    subscriptionsRepository
      .deleteExtraCharge(id = extraChargeId, subscriptionId = subscriptionId, userId = userId)
      .map(_ => DeleteSubscriptionExtraCharge.Response())
  }
}
