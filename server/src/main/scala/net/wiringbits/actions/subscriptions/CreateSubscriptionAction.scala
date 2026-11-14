package net.wiringbits.actions.subscriptions

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.CreateSubscription
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.config.SubscriptionsConfig
import net.wiringbits.repositories.SubscriptionsRepository
import net.wiringbits.repositories.models.Subscription

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateSubscriptionAction @Inject() (
    subscriptionsRepository: SubscriptionsRepository,
    subscriptionsConfig: SubscriptionsConfig
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(userId: UUID, request: CreateSubscription.Request): Future[CreateSubscription.Response] = {
    for {
      _ <- validateFields(request)
      _ <- ensureBelowLimit(userId)
      now = clock.instant()
      create = request
        .into[Subscription.Create]
        .withFieldConst(_.id, UuidCreator.getTimeOrderedEpoch())
        .withFieldConst(_.userId, userId)
        .withFieldComputed(_.name, _.name.string)
        .withFieldConst(_.createdAt, now)
        .withFieldConst(_.updatedAt, now)
        .transform
      _ <- subscriptionsRepository.create(create)
    } yield CreateSubscription.Response(create.id)
  }

  private def validateFields(request: CreateSubscription.Request): Future[Unit] =
    SubscriptionValidator
      .validate(
        request.cadence,
        request.intervalDays,
        amountMin = request.amountMin,
        amountMax = request.amountMax
      )
      .fold(error => Future.failed(new RuntimeException(error)), _ => Future.unit)

  private def ensureBelowLimit(userId: UUID): Future[Unit] =
    subscriptionsRepository.count(userId).flatMap { count =>
      if (count >= subscriptionsConfig.maxPerUser)
        Future.failed(
          new RuntimeException(s"Maximum number of subscriptions (${subscriptionsConfig.maxPerUser}) reached")
        )
      else Future.unit
    }
}
