package net.wiringbits.actions.subscriptions

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.AddSubscriptionManualPayment
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.repositories.SubscriptionsRepository
import net.wiringbits.repositories.models.SubscriptionManualPayment

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class AddSubscriptionManualPaymentAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext,
    clock: Clock
) {

  def apply(
      subscriptionId: UUID,
      userId: UUID,
      request: AddSubscriptionManualPayment.Request
  ): Future[AddSubscriptionManualPayment.Response] = {
    for {
      _ <- SubscriptionValidator
        .validateManualPaymentAmount(request.amount)
        .fold(error => Future.failed(new RuntimeException(error)), _ => Future.unit)
      now = clock.instant()
      payment = request
        .into[SubscriptionManualPayment]
        .withFieldConst(_.id, UuidCreator.getTimeOrderedEpoch())
        .withFieldConst(_.userId, userId)
        .withFieldConst(_.subscriptionId, subscriptionId)
        // The note decodes trimmed, so a blank one arrives as an empty string and means "no note".
        .withFieldConst(_.note, request.note.filter(_.string.nonEmpty))
        .withFieldConst(_.createdAt, now)
        .withFieldConst(_.updatedAt, now)
        .transform
      _ <- subscriptionsRepository.addManualPayment(
        payment,
        maxPerSubscription = SubscriptionValidator.MaxManualPaymentsPerSubscription
      )
    } yield AddSubscriptionManualPayment.Response(payment.id)
  }
}
