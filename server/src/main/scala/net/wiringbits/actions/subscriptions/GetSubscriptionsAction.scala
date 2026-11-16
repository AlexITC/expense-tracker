package net.wiringbits.actions.subscriptions

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.{GetSubscriptions, SubscriptionExtraCharge, SubscriptionTerm}
import net.wiringbits.repositories.SubscriptionsRepository
import net.wiringbits.repositories.models.Subscription

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetSubscriptionsAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID): Future[GetSubscriptions.Response] = {
    subscriptionsRepository
      .all(userId)
      .map(rows => GetSubscriptions.Response(rows.map(toResponse)))
  }

  private def toResponse(row: Subscription.WithDetails): GetSubscriptions.Response.Subscription = {
    row.subscription
      .into[GetSubscriptions.Response.Subscription]
      .withFieldConst(_.terms, row.terms.map(_.transformInto[SubscriptionTerm]))
      .withFieldConst(_.extraCharges, row.extraCharges.map(_.transformInto[SubscriptionExtraCharge]))
      .transform
  }
}
