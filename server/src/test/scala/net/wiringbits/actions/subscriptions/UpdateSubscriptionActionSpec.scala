package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod, UpdateSubscription}
import net.wiringbits.common.models.SubscriptionName
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class UpdateSubscriptionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): UpdateSubscriptionAction =
    new UpdateSubscriptionAction(repositories.subscriptions)(using executionContext)

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private def request(
      name: String = uniqueName("Mobile"),
      cadence: SubscriptionCadence = SubscriptionCadence.EveryNDays,
      intervalDays: Option[Int] = Some(30),
      amountMin: Option[BigDecimal] = Some(BigDecimal("20.00")),
      amountMax: Option[BigDecimal] = Some(BigDecimal("25.00")),
      paymentMethod: SubscriptionPaymentMethod = SubscriptionPaymentMethod.Cash,
      categoryId: Option[UUID] = None,
      startDate: LocalDate = LocalDate.of(2024, 2, 1)
  ): UpdateSubscription.Request =
    UpdateSubscription.Request(
      name = SubscriptionName.trusted(name),
      cadence = cadence,
      intervalDays = intervalDays,
      amountMin = amountMin,
      amountMax = amountMax,
      paymentMethod = paymentMethod,
      categoryId = categoryId,
      startDate = startDate
    )

  "apply" should {
    "update every field" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = uniqueName("Category")).futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue
      val req = request(categoryId = Some(category.id))

      newAction().apply(subscriptionId = existing.id, userId = user.id, req).futureValue

      val stored = repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value
      stored.name must be(req.name.string)
      stored.cadence must be(SubscriptionCadence.EveryNDays)
      stored.intervalDays must be(Some(30))
      stored.amountMin must be(Some(BigDecimal("20.00")))
      stored.amountMax must be(Some(BigDecimal("25.00")))
      stored.paymentMethod must be(SubscriptionPaymentMethod.Cash)
      stored.categoryId must be(Some(category.id))
      stored.startDate must be(LocalDate.of(2024, 2, 1))
    }

    "reject a name already used by a sibling" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val sibling = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      val error = newAction()
        .apply(subscriptionId = existing.id, userId = user.id, request(name = sibling.name))
        .failed
        .futureValue
      error.getMessage must include("A subscription with that name already exists")
      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value.name must be(existing.name)
    }

    "reject invalid fields" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      val error = newAction()
        .apply(subscriptionId = existing.id, userId = user.id, request(amountMin = Some(BigDecimal("30.00"))))
        .failed
        .futureValue
      error.getMessage must be("Minimum amount must not be greater than the maximum amount")
      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value.name must be(existing.name)
    }

    "fail for another user's subscription without changing it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Phone")).futureValue

      val error = newAction().apply(subscriptionId = existing.id, userId = other.id, request()).failed.futureValue
      error.getMessage must include("Subscription not found")
      val stored = repositories.subscriptions.find(id = existing.id, userId = owner.id).futureValue.value
      stored.name must be(existing.name)
      stored.cadence must be(existing.cadence)
    }

    "reject another user's category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val category = createCategory(other.id, name = uniqueName("Category")).futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      val error = newAction()
        .apply(subscriptionId = existing.id, userId = user.id, request(categoryId = Some(category.id)))
        .failed
        .futureValue
      error.getMessage must include("Category not found")
      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value.categoryId must be(None)
    }
  }
}
