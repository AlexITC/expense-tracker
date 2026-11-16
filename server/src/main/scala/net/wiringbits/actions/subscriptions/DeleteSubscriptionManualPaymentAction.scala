package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.DeleteSubscriptionManualPayment
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteSubscriptionManualPaymentAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(
      subscriptionId: UUID,
      manualPaymentId: UUID,
      userId: UUID
  ): Future[DeleteSubscriptionManualPayment.Response] = {
    subscriptionsRepository
      .deleteManualPayment(id = manualPaymentId, subscriptionId = subscriptionId, userId = userId)
      .map(_ => DeleteSubscriptionManualPayment.Response())
  }
}
