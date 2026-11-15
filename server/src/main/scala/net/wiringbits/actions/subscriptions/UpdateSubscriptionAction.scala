package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.UpdateSubscription
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateSubscriptionAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(
      subscriptionId: UUID,
      userId: UUID,
      request: UpdateSubscription.Request
  ): Future[UpdateSubscription.Response] = {
    for {
      _ <- validateFields(request)
      _ <- subscriptionsRepository.update(
        id = subscriptionId,
        userId = userId,
        name = request.name.string,
        cadence = request.cadence,
        intervalDays = request.intervalDays,
        intervalMonths = request.intervalMonths,
        paymentMethod = request.paymentMethod,
        categoryId = request.categoryId
      )
    } yield UpdateSubscription.Response()
  }

  private def validateFields(request: UpdateSubscription.Request): Future[Unit] =
    SubscriptionValidator
      .validateCadence(
        cadence = request.cadence,
        intervalDays = request.intervalDays,
        intervalMonths = request.intervalMonths
      )
      .fold(error => Future.failed(new RuntimeException(error)), _ => Future.unit)
}
