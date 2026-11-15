package net.wiringbits.actions.subscriptions

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.subscriptions.ResumeSubscription
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ResumeSubscriptionAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(
      subscriptionId: UUID,
      userId: UUID,
      request: ResumeSubscription.Request
  ): Future[ResumeSubscription.Response] = {
    for {
      _ <- SubscriptionValidator
        .validateAmountRange(amountMin = request.amountMin, amountMax = request.amountMax)
        .fold(error => Future.failed(new RuntimeException(error)), _ => Future.unit)
      _ <- subscriptionsRepository.resume(
        id = subscriptionId,
        userId = userId,
        newTermId = UuidCreator.getTimeOrderedEpoch(),
        effectiveFrom = request.effectiveFrom,
        amountMin = request.amountMin,
        amountMax = request.amountMax
      )
    } yield ResumeSubscription.Response()
  }
}
