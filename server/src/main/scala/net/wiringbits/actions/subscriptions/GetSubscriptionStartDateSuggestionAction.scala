package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.GetSubscriptionStartDateSuggestion
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetSubscriptionStartDateSuggestionAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID, merchantId: UUID): Future[GetSubscriptionStartDateSuggestion.Response] = {
    subscriptionsRepository
      .earliestExpenseDateForMerchant(userId = userId, merchantId = merchantId)
      .map(GetSubscriptionStartDateSuggestion.Response(_))
  }
}
