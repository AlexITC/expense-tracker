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

class DeleteSubscriptionExtraChargeActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): DeleteSubscriptionExtraChargeAction =
    new DeleteSubscriptionExtraChargeAction(repositories.subscriptions)(using executionContext)

  private def addCharge(subscriptionId: UUID, userId: UUID)(using repositories: RepositoryComponents): UUID =
    new AddSubscriptionExtraChargeAction(repositories.subscriptions)(using executionContext, Clock.systemUTC())
      .apply(
        subscriptionId,
        userId,
        AddSubscriptionExtraCharge.Request(
          label = SubscriptionExtraChargeLabel.trusted("Setup fee"),
          amount = BigDecimal("25.00"),
          recurrence = SubscriptionExtraChargeRecurrence.OneOff,
          dueDate = LocalDate.of(2025, 1, 10)
        )
      )
      .futureValue
      .id

  private def storedIds(userId: UUID)(using repositories: RepositoryComponents): List[UUID] =
    repositories.database.withConnection { implicit conn => SubscriptionExtraChargeDAO.allForUser(userId).map(_.id) }

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  "apply" should {
    "remove the extra charge" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val removed = addCharge(existing.id, user.id)
      val kept = addCharge(existing.id, user.id)

      newAction().apply(subscriptionId = existing.id, extraChargeId = removed, userId = user.id).futureValue

      storedIds(user.id) must be(List(kept))
    }

    "fail for another user's extra charge and keep it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Gym")).futureValue
      val charge = addCharge(existing.id, owner.id)

      val error =
        newAction().apply(subscriptionId = existing.id, extraChargeId = charge, userId = other.id).failed.futureValue
      error.getMessage must be("Extra charge not found")
      storedIds(owner.id) must be(List(charge))
    }

    "fail when the extra charge belongs to a different subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val first = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val second = createSubscription(user.id, name = uniqueName("Phone")).futureValue
      val charge = addCharge(first.id, user.id)

      val error =
        newAction().apply(subscriptionId = second.id, extraChargeId = charge, userId = user.id).failed.futureValue
      error.getMessage must be("Extra charge not found")
      storedIds(user.id) must be(List(charge))
    }
  }
}
