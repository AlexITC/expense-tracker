package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{AddSubscriptionExtraCharge, SubscriptionExtraChargeRecurrence}
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.common.models.SubscriptionExtraChargeLabel
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.daos.SubscriptionExtraChargeDAO
import net.wiringbits.repositories.models.SubscriptionExtraCharge
import org.scalatest.LoneElement.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, LocalDate}
import java.util.UUID

class AddSubscriptionExtraChargeActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): AddSubscriptionExtraChargeAction =
    new AddSubscriptionExtraChargeAction(repositories.subscriptions)(using executionContext, Clock.systemUTC())

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private def request(
      label: String = "Yearly fee",
      amount: BigDecimal = BigDecimal("12.00"),
      recurrence: SubscriptionExtraChargeRecurrence = SubscriptionExtraChargeRecurrence.Yearly,
      dueDate: LocalDate = LocalDate.of(2025, 3, 1)
  ): AddSubscriptionExtraCharge.Request =
    AddSubscriptionExtraCharge.Request(
      label = SubscriptionExtraChargeLabel.trusted(label),
      amount = amount,
      recurrence = recurrence,
      dueDate = dueDate
    )

  private def storedCharges(userId: UUID)(using repositories: RepositoryComponents): List[SubscriptionExtraCharge] =
    repositories.database.withConnection { implicit conn => SubscriptionExtraChargeDAO.allForUser(userId) }

  "apply" should {
    "store the extra charge on the subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue

      val response = newAction().apply(existing.id, user.id, request()).futureValue

      val stored = storedCharges(user.id).loneElement
      stored.id must be(response.id)
      stored.subscriptionId must be(existing.id)
      stored.label must be("Yearly fee")
      stored.amount must be(BigDecimal("12.00"))
      stored.recurrence must be(SubscriptionExtraChargeRecurrence.Yearly)
      stored.dueDate must be(LocalDate.of(2025, 3, 1))
    }

    "reject an invalid amount without storing it" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue

      val error = newAction().apply(existing.id, user.id, request(amount = BigDecimal("0"))).failed.futureValue
      error.getMessage must be("Amount must be greater than zero")
      storedCharges(user.id) must be(List.empty)
    }

    "reject another user's subscription without storing it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Gym")).futureValue

      val error = newAction().apply(existing.id, other.id, request()).failed.futureValue
      error.getMessage must be("Subscription not found")
      storedCharges(owner.id) must be(List.empty)
      storedCharges(other.id) must be(List.empty)
    }

    "reject a charge beyond the per-subscription cap" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val max = SubscriptionValidator.MaxExtraChargesPerSubscription
      (1 to max).foreach { i => newAction().apply(existing.id, user.id, request(label = s"Fee $i")).futureValue }

      val error = newAction().apply(existing.id, user.id, request(label = "One too many")).failed.futureValue
      error.getMessage must be(s"Maximum number of extra charges ($max) reached")
      storedCharges(user.id).size must be(max)
    }
  }
}
