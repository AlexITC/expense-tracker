package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.AddSubscriptionManualPayment
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.common.models.SubscriptionManualPaymentNote
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.models.SubscriptionManualPayment
import org.scalatest.LoneElement.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, LocalDate}
import java.util.UUID

class AddSubscriptionManualPaymentActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): AddSubscriptionManualPaymentAction =
    new AddSubscriptionManualPaymentAction(repositories.subscriptions)(using executionContext, Clock.systemUTC())

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private def request(
      paidDate: LocalDate = LocalDate.of(2025, 3, 1),
      amount: BigDecimal = BigDecimal("15.00"),
      note: Option[String] = Some("Paid in cash")
  ): AddSubscriptionManualPayment.Request =
    AddSubscriptionManualPayment.Request(
      paidDate = paidDate,
      amount = amount,
      note = note.map(SubscriptionManualPaymentNote.trusted)
    )

  private def storedPayments(userId: UUID)(using repositories: RepositoryComponents): List[SubscriptionManualPayment] =
    repositories.subscriptions.manualPayments(userId).futureValue

  "apply" should {
    "store the manual payment on the subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue

      val response = newAction().apply(existing.id, user.id, request()).futureValue

      val stored = storedPayments(user.id).loneElement
      stored.id must be(response.id)
      stored.userId must be(user.id)
      stored.subscriptionId must be(existing.id)
      stored.paidDate must be(LocalDate.of(2025, 3, 1))
      stored.amount must be(BigDecimal("15.00"))
      stored.note.map(_.string) must be(Some("Paid in cash"))
    }

    "store a payment without a note" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue

      newAction().apply(existing.id, user.id, request(note = None)).futureValue

      storedPayments(user.id).loneElement.note must be(None)
    }

    "reject an invalid amount without storing it" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue

      val error = newAction().apply(existing.id, user.id, request(amount = BigDecimal("0"))).failed.futureValue
      error.getMessage must be("Amount must be greater than zero")
      storedPayments(user.id) must be(List.empty)
    }

    "reject another user's subscription without storing it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Gym")).futureValue

      val error = newAction().apply(existing.id, other.id, request()).failed.futureValue
      error.getMessage must be("Subscription not found")
      storedPayments(owner.id) must be(List.empty)
      storedPayments(other.id) must be(List.empty)
    }

    "reject a payment beyond the per-subscription cap" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val max = SubscriptionValidator.MaxManualPaymentsPerSubscription
      (1 to max).foreach { i =>
        newAction().apply(existing.id, user.id, request(paidDate = LocalDate.of(2020, 1, 1).plusDays(i))).futureValue
      }

      val error = newAction().apply(existing.id, user.id, request()).failed.futureValue
      error.getMessage must be(s"Maximum number of manual payments ($max) reached")
      storedPayments(user.id).size must be(max)
    }

    "remove the payments when the subscription is deleted" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      newAction().apply(existing.id, user.id, request()).futureValue

      repositories.subscriptions.delete(id = existing.id, userId = user.id).futureValue

      storedPayments(user.id) must be(List.empty)
    }
  }
}
