package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.ChangeSubscriptionPrice
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class ChangeSubscriptionPriceActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): ChangeSubscriptionPriceAction =
    new ChangeSubscriptionPriceAction(repositories.subscriptions)(using executionContext)

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private def request(
      effectiveFrom: LocalDate = LocalDate.of(2025, 6, 1),
      amountMin: Option[BigDecimal] = Some(BigDecimal("12.00")),
      amountMax: Option[BigDecimal] = Some(BigDecimal("15.00"))
  ): ChangeSubscriptionPrice.Request =
    ChangeSubscriptionPrice.Request(effectiveFrom = effectiveFrom, amountMin = amountMin, amountMax = amountMax)

  "apply" should {
    "close the open term and open a new term with the new amounts" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(
        user.id,
        name = uniqueName("Phone"),
        amountMin = Some(BigDecimal("10.00")),
        amountMax = Some(BigDecimal("10.00")),
        startDate = LocalDate.of(2024, 2, 1)
      ).futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id, request()).futureValue

      val terms = repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue
      terms.map(t => (t.effectiveFrom, t.effectiveTo, t.amountMin, t.amountMax)) must be(
        List(
          (
            LocalDate.of(2024, 2, 1),
            Some(LocalDate.of(2025, 6, 1)),
            Some(BigDecimal("10.00")),
            Some(BigDecimal("10.00"))
          ),
          (LocalDate.of(2025, 6, 1), None, Some(BigDecimal("12.00")), Some(BigDecimal("15.00")))
        )
      )
      terms.head.id must be(existing.firstTerm.id)
      terms.foreach(_.userId must be(user.id))
    }

    "bump the subscription's updated time" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id, request()).futureValue

      val stored = repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value
      stored.updatedAt.isAfter(existing.updatedAt) must be(true)
    }

    "reject a date on or before the open term's start" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Phone"), startDate = LocalDate.of(2024, 2, 1)).futureValue

      val error = newAction()
        .apply(subscriptionId = existing.id, userId = user.id, request(effectiveFrom = LocalDate.of(2024, 2, 1)))
        .failed
        .futureValue
      error.getMessage must be("The new price must start after the current term starts on 2024-02-01")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue must be(
        List(existing.firstTerm)
      )
    }

    "reject a cancelled subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Phone"), startDate = LocalDate.of(2024, 2, 1)).futureValue
      repositories.subscriptions
        .cancel(id = existing.id, userId = user.id, effectiveTo = LocalDate.of(2025, 1, 1))
        .futureValue

      val error = newAction().apply(subscriptionId = existing.id, userId = user.id, request()).failed.futureValue
      error.getMessage must be("Subscription is cancelled, resume it instead")
    }

    "reject an invalid amount range without changing the terms" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      val error = newAction()
        .apply(subscriptionId = existing.id, userId = user.id, request(amountMax = None))
        .failed
        .futureValue
      error.getMessage must be("Minimum and maximum amounts must be both set or both empty")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue must be(
        List(existing.firstTerm)
      )
    }

    "fail for another user's subscription without changing it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Phone")).futureValue

      val error = newAction().apply(subscriptionId = existing.id, userId = other.id, request()).failed.futureValue
      error.getMessage must be("Subscription not found")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = owner.id).futureValue must be(
        List(existing.firstTerm)
      )
    }
  }
}
