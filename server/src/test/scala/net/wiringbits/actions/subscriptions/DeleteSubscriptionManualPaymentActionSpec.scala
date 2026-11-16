package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.AddSubscriptionManualPayment
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, LocalDate}
import java.util.UUID

class DeleteSubscriptionManualPaymentActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): DeleteSubscriptionManualPaymentAction =
    new DeleteSubscriptionManualPaymentAction(repositories.subscriptions)(using executionContext)

  private def addPayment(subscriptionId: UUID, userId: UUID)(using repositories: RepositoryComponents): UUID =
    new AddSubscriptionManualPaymentAction(repositories.subscriptions)(using executionContext, Clock.systemUTC())
      .apply(
        subscriptionId,
        userId,
        AddSubscriptionManualPayment.Request(
          paidDate = LocalDate.of(2025, 1, 10),
          amount = BigDecimal("25.00"),
          note = None
        )
      )
      .futureValue
      .id

  private def storedIds(userId: UUID)(using repositories: RepositoryComponents): List[UUID] =
    repositories.subscriptions.manualPayments(userId).futureValue.map(_.id)

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  "apply" should {
    "remove the manual payment" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val removed = addPayment(existing.id, user.id)
      val kept = addPayment(existing.id, user.id)

      newAction().apply(subscriptionId = existing.id, manualPaymentId = removed, userId = user.id).futureValue

      storedIds(user.id) must be(List(kept))
    }

    "fail for another user's manual payment and keep it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Gym")).futureValue
      val payment = addPayment(existing.id, owner.id)

      val error =
        newAction().apply(subscriptionId = existing.id, manualPaymentId = payment, userId = other.id).failed.futureValue
      error.getMessage must be("Manual payment not found")
      storedIds(owner.id) must be(List(payment))
    }

    "fail when the manual payment belongs to a different subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val first = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val second = createSubscription(user.id, name = uniqueName("Streaming")).futureValue
      val payment = addPayment(first.id, user.id)

      val error =
        newAction().apply(subscriptionId = second.id, manualPaymentId = payment, userId = user.id).failed.futureValue
      error.getMessage must be("Manual payment not found")
      storedIds(user.id) must be(List(payment))
    }

    "fail for an unknown manual payment" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val payment = addPayment(existing.id, user.id)

      val error = newAction()
        .apply(subscriptionId = existing.id, manualPaymentId = UUID.randomUUID(), userId = user.id)
        .failed
        .futureValue
      error.getMessage must be("Manual payment not found")
      storedIds(user.id) must be(List(payment))
    }
  }
}
