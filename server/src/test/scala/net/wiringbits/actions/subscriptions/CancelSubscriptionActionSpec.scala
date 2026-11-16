package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.CancelSubscription
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class CancelSubscriptionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): CancelSubscriptionAction =
    new CancelSubscriptionAction(repositories.subscriptions)(using executionContext)

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  "apply" should {
    "end the open term at the cancellation date" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Gym"), startDate = LocalDate.of(2024, 2, 1)).futureValue

      newAction()
        .apply(existing.id, user.id, CancelSubscription.Request(effectiveTo = LocalDate.of(2025, 3, 1)))
        .futureValue

      val terms = repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue
      terms.map(t => (t.id, t.effectiveFrom, t.effectiveTo)) must be(
        List((existing.firstTerm.id, LocalDate.of(2024, 2, 1), Some(LocalDate.of(2025, 3, 1))))
      )
    }

    "reject an already cancelled subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Gym"), startDate = LocalDate.of(2024, 2, 1)).futureValue
      val request = CancelSubscription.Request(effectiveTo = LocalDate.of(2025, 3, 1))
      newAction().apply(existing.id, user.id, request).futureValue

      val error = newAction()
        .apply(existing.id, user.id, CancelSubscription.Request(effectiveTo = LocalDate.of(2025, 4, 1)))
        .failed
        .futureValue
      error.getMessage must be("Subscription is already cancelled")
      repositories.subscriptions
        .terms(subscriptionId = existing.id, userId = user.id)
        .futureValue
        .map(_.effectiveTo) must be(List(Some(LocalDate.of(2025, 3, 1))))
    }

    "reject a date on or before the open term's start" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Gym"), startDate = LocalDate.of(2024, 2, 1)).futureValue

      val error = newAction()
        .apply(existing.id, user.id, CancelSubscription.Request(effectiveTo = LocalDate.of(2024, 2, 1)))
        .failed
        .futureValue
      error.getMessage must be("The cancellation date must be after the current term starts on 2024-02-01")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue must be(
        List(existing.firstTerm)
      )
    }

    "fail for another user's subscription without changing it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Gym")).futureValue

      val error = newAction()
        .apply(existing.id, other.id, CancelSubscription.Request(effectiveTo = LocalDate.of(2025, 3, 1)))
        .failed
        .futureValue
      error.getMessage must be("Subscription not found")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = owner.id).futureValue must be(
        List(existing.firstTerm)
      )
    }
  }
}
