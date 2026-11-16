package net.wiringbits.actions.subscriptions

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.AddSubscriptionExtraCharge
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.repositories.SubscriptionsRepository
import net.wiringbits.repositories.models.SubscriptionExtraCharge

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class AddSubscriptionExtraChargeAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext,
    clock: Clock
) {

  def apply(
      subscriptionId: UUID,
      userId: UUID,
      request: AddSubscriptionExtraCharge.Request
  ): Future[AddSubscriptionExtraCharge.Response] = {
    for {
      _ <- SubscriptionValidator
        .validateExtraChargeAmount(request.amount)
        .fold(error => Future.failed(new RuntimeException(error)), _ => Future.unit)
      now = clock.instant()
      charge = request
        .into[SubscriptionExtraCharge]
        .withFieldConst(_.id, UuidCreator.getTimeOrderedEpoch())
        .withFieldConst(_.userId, userId)
        .withFieldConst(_.subscriptionId, subscriptionId)
        .withFieldComputed(_.label, _.label.string)
        .withFieldConst(_.createdAt, now)
        .withFieldConst(_.updatedAt, now)
        .transform
      _ <- subscriptionsRepository.addExtraCharge(
        charge,
        maxPerSubscription = SubscriptionValidator.MaxExtraChargesPerSubscription
      )
    } yield AddSubscriptionExtraCharge.Response(charge.id)
  }
}
