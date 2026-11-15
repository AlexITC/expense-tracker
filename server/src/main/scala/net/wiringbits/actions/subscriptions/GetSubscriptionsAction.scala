package net.wiringbits.actions.subscriptions

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.GetSubscriptions
import net.wiringbits.repositories.SubscriptionsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetSubscriptionsAction @Inject() (subscriptionsRepository: SubscriptionsRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID): Future[GetSubscriptions.Response] = {
    subscriptionsRepository
      .all(userId)
      .map(rows => GetSubscriptions.Response(rows.map(_.transformInto[GetSubscriptions.Response.Subscription])))
  }
}
