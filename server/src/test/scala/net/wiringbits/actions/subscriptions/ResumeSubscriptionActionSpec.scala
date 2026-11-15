package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{CancelSubscription, ChangeSubscriptionPrice, ResumeSubscription}
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class ResumeSubscriptionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): ResumeSubscriptionAction =
    new ResumeSubscriptionAction(repositories.subscriptions)(using executionContext)

  private def cancel(subscriptionId: UUID, userId: UUID, effectiveTo: LocalDate)(using
      repositories: RepositoryComponents
  ): Unit =
    new CancelSubscriptionAction(repositories.subscriptions)(using executionContext)
      .apply(subscriptionId, userId, CancelSubscription.Request(effectiveTo = effectiveTo))
      .futureValue

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private def request(
      effectiveFrom: LocalDate = LocalDate.of(2025, 6, 1),
      amountMin: Option[BigDecimal] = Some(BigDecimal("30.00")),
      amountMax: Option[BigDecimal] = Some(BigDecimal("30.00"))
  ): ResumeSubscription.Request =
    ResumeSubscription.Request(effectiveFrom = effectiveFrom, amountMin = amountMin, amountMax = amountMax)

  "apply" should {
    "preserve the whole history across a price change, a cancellation and a resume" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val existing = createSubscription(
          user.id,
          name = uniqueName("Gym"),
          amountMin = Some(BigDecimal("20.00")),
          amountMax = Some(BigDecimal("20.00")),
          startDate = LocalDate.of(2024, 1, 1)
        ).futureValue
        new ChangeSubscriptionPriceAction(repositories.subscriptions)(using executionContext)
          .apply(
            existing.id,
            user.id,
            ChangeSubscriptionPrice.Request(
              effectiveFrom = LocalDate.of(2024, 7, 1),
              amountMin = Some(BigDecimal("25.00")),
              amountMax = Some(BigDecimal("25.00"))
            )
          )
          .futureValue
        cancel(existing.id, user.id, LocalDate.of(2025, 1, 1))

        newAction().apply(existing.id, user.id, request(effectiveFrom = LocalDate.of(2025, 6, 1))).futureValue

        val terms = repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue
        terms.map(t => (t.effectiveFrom, t.effectiveTo, t.amountMin)) must be(
          List(
            (LocalDate.of(2024, 1, 1), Some(LocalDate.of(2024, 7, 1)), Some(BigDecimal("20.00"))),
            (LocalDate.of(2024, 7, 1), Some(LocalDate.of(2025, 1, 1)), Some(BigDecimal("25.00"))),
            (LocalDate.of(2025, 6, 1), None, Some(BigDecimal("30.00")))
          )
        )
        terms.head.id must be(existing.firstTerm.id)
    }

    "accept a resume on the last term's end date" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Gym"), startDate = LocalDate.of(2024, 1, 1)).futureValue
      cancel(existing.id, user.id, LocalDate.of(2025, 1, 1))

      newAction().apply(existing.id, user.id, request(effectiveFrom = LocalDate.of(2025, 1, 1))).futureValue

      repositories.subscriptions
        .terms(subscriptionId = existing.id, userId = user.id)
        .futureValue
        .map(t => (t.effectiveFrom, t.effectiveTo)) must be(
        List(
          (LocalDate.of(2024, 1, 1), Some(LocalDate.of(2025, 1, 1))),
          (LocalDate.of(2025, 1, 1), None)
        )
      )
    }

    "reject a subscription that is not cancelled" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue

      val error = newAction().apply(existing.id, user.id, request()).failed.futureValue
      error.getMessage must be("Subscription is not cancelled")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue must be(
        List(existing.firstTerm)
      )
    }

    "reject a date before the last term's end" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Gym"), startDate = LocalDate.of(2024, 1, 1)).futureValue
      cancel(existing.id, user.id, LocalDate.of(2025, 1, 1))

      val error =
        newAction().apply(existing.id, user.id, request(effectiveFrom = LocalDate.of(2024, 12, 31))).failed.futureValue
      error.getMessage must be("The resume date must be on or after 2025-01-01")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue.size must be(1)
    }

    "reject an invalid amount range" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createSubscription(user.id, name = uniqueName("Gym"), startDate = LocalDate.of(2024, 1, 1)).futureValue
      cancel(existing.id, user.id, LocalDate.of(2025, 1, 1))

      val error = newAction()
        .apply(existing.id, user.id, request(amountMin = Some(BigDecimal("0")), amountMax = Some(BigDecimal("5"))))
        .failed
        .futureValue
      error.getMessage must be("Minimum amount must be greater than zero")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue.size must be(1)
    }

    "fail for another user's subscription without changing it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing =
        createSubscription(owner.id, name = uniqueName("Gym"), startDate = LocalDate.of(2024, 1, 1)).futureValue
      cancel(existing.id, owner.id, LocalDate.of(2025, 1, 1))

      val error = newAction().apply(existing.id, other.id, request()).failed.futureValue
      error.getMessage must be("Subscription not found")
      repositories.subscriptions.terms(subscriptionId = existing.id, userId = owner.id).futureValue.size must be(1)
    }
  }
}
