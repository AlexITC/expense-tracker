package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{AddSubscriptionExtraCharge, SubscriptionExtraChargeRecurrence}
import net.wiringbits.common.models.SubscriptionExtraChargeLabel
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.daos.SubscriptionExtraChargeDAO
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, LocalDate}
import java.util.UUID

class DeleteSubscriptionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): DeleteSubscriptionAction =
    new DeleteSubscriptionAction(repositories.subscriptions)(using executionContext)

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  "apply" should {
    "remove the subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id).futureValue

      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue must be(None)
    }

    "remove the subscription's extra charges" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue
      new AddSubscriptionExtraChargeAction(repositories.subscriptions)(using executionContext, Clock.systemUTC())
        .apply(
          existing.id,
          user.id,
          AddSubscriptionExtraCharge.Request(
            label = SubscriptionExtraChargeLabel.trusted("Yearly fee"),
            amount = BigDecimal("12.00"),
            recurrence = SubscriptionExtraChargeRecurrence.Yearly,
            dueDate = LocalDate.of(2025, 3, 1)
          )
        )
        .futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id).futureValue

      repositories.database.withConnection { implicit conn =>
        SubscriptionExtraChargeDAO.allForUser(user.id)
      } must be(List.empty)
    }

    "fail for another user's subscription and keep it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Phone")).futureValue

      val error = newAction().apply(subscriptionId = existing.id, userId = other.id).failed.futureValue
      error.getMessage must include("Subscription not found")
      repositories.subscriptions.find(id = existing.id, userId = owner.id).futureValue must not be empty
    }
  }
}
