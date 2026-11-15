package net.wiringbits.repositories

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate, ZoneOffset}
import java.util.UUID

class SubscriptionsRepositorySpec extends RepositorySpec with RepositoryUtils {

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private val fixedInstant = Instant.parse("2024-06-01T12:00:00Z")
  private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

  "all" should {
    "return the user's subscriptions ordered by name, excluding other users' rows" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val other = createUser().futureValue
        val base = UUID.randomUUID().toString
        createSubscription(user.id, name = s"$base B").futureValue
        createSubscription(user.id, name = s"$base A").futureValue
        createSubscription(other.id, name = s"$base C").futureValue

        val result = repositories.subscriptions.all(user.id).futureValue
        result.map(_.name) must be(List(s"$base A", s"$base B"))
    }
  }

  "create" should {
    "persist every field" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = uniqueName("Category")).futureValue
      val request = createSubscription(
        user.id,
        name = uniqueName("Streaming"),
        cadence = SubscriptionCadence.Yearly,
        amountMin = Some(BigDecimal("9.99")),
        amountMax = Some(BigDecimal("12.50")),
        paymentMethod = SubscriptionPaymentMethod.CardPortal,
        categoryId = Some(category.id),
        startDate = LocalDate.of(2024, 3, 1)
      ).futureValue

      val stored = repositories.subscriptions.find(id = request.id, userId = user.id).futureValue.value
      stored.name must be(request.name)
      stored.cadence must be(SubscriptionCadence.Yearly)
      stored.intervalDays must be(None)
      stored.amountMin must be(Some(BigDecimal("9.99")))
      stored.amountMax must be(Some(BigDecimal("12.50")))
      stored.paymentMethod must be(SubscriptionPaymentMethod.CardPortal)
      stored.categoryId must be(Some(category.id))
      stored.startDate must be(LocalDate.of(2024, 3, 1))
    }

    "round-trip an every-n-days cadence with its interval" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val request = createSubscription(
        user.id,
        name = uniqueName("Water"),
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(45)
      ).futureValue

      val stored = repositories.subscriptions.find(id = request.id, userId = user.id).futureValue.value
      stored.cadence must be(SubscriptionCadence.EveryNDays)
      stored.intervalDays must be(Some(45))
    }

    "reject a duplicate name for the same user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val name = uniqueName("Gym")
      createSubscription(user.id, name = name).futureValue

      val error = createSubscription(user.id, name = name).failed.futureValue
      error.getMessage must include("A subscription with that name already exists")
    }

    "allow the same name for a different user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val name = uniqueName("Gym")
      createSubscription(user.id, name = name).futureValue
      createSubscription(other.id, name = name).futureValue

      repositories.subscriptions.all(other.id).futureValue.map(_.name) must be(List(name))
    }

    "reject a category owned by another user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val foreignCategory = createCategory(other.id, name = uniqueName("Category")).futureValue

      val error = createSubscription(
        user.id,
        name = uniqueName("Phone"),
        categoryId = Some(foreignCategory.id)
      ).failed.futureValue
      error.getMessage must include("Category not found")
    }
  }

  "update" should {
    "change the stored fields" in withRepositories(clock = fixedClock) { implicit repositories =>
      val user = createUser().futureValue
      val request = createSubscription(user.id, name = uniqueName("Phone")).futureValue
      val newName = uniqueName("Mobile")

      repositories.subscriptions
        .update(
          id = request.id,
          userId = user.id,
          name = newName,
          cadence = SubscriptionCadence.EveryNDays,
          intervalDays = Some(30),
          amountMin = Some(BigDecimal("20.00")),
          amountMax = Some(BigDecimal("20.00")),
          paymentMethod = SubscriptionPaymentMethod.Cash,
          categoryId = None,
          startDate = LocalDate.of(2024, 2, 1)
        )
        .futureValue

      val stored = repositories.subscriptions.find(id = request.id, userId = user.id).futureValue.value
      stored.name must be(newName)
      stored.cadence must be(SubscriptionCadence.EveryNDays)
      stored.intervalDays must be(Some(30))
      stored.amountMin must be(Some(BigDecimal("20.00")))
      stored.amountMax must be(Some(BigDecimal("20.00")))
      stored.paymentMethod must be(SubscriptionPaymentMethod.Cash)
      stored.categoryId must be(None)
      stored.startDate must be(LocalDate.of(2024, 2, 1))
      stored.updatedAt must be(fixedInstant)
    }

    "clear the optional fields" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = uniqueName("Category")).futureValue
      val request = createSubscription(
        user.id,
        name = uniqueName("Water"),
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(45),
        amountMin = Some(BigDecimal("10.00")),
        amountMax = Some(BigDecimal("15.00")),
        categoryId = Some(category.id)
      ).futureValue

      repositories.subscriptions
        .update(
          id = request.id,
          userId = user.id,
          name = request.name,
          cadence = SubscriptionCadence.Monthly,
          intervalDays = None,
          amountMin = None,
          amountMax = None,
          paymentMethod = request.paymentMethod,
          categoryId = None,
          startDate = request.startDate
        )
        .futureValue

      val stored = repositories.subscriptions.find(id = request.id, userId = user.id).futureValue.value
      stored.cadence must be(SubscriptionCadence.Monthly)
      stored.intervalDays must be(None)
      stored.amountMin must be(None)
      stored.amountMax must be(None)
      stored.categoryId must be(None)
    }

    "reject a category owned by another user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val request = createSubscription(user.id, name = uniqueName("Phone")).futureValue
      val foreignCategory = createCategory(other.id, name = uniqueName("Category")).futureValue

      val error = repositories.subscriptions
        .update(
          id = request.id,
          userId = user.id,
          name = request.name,
          cadence = request.cadence,
          intervalDays = request.intervalDays,
          amountMin = request.amountMin,
          amountMax = request.amountMax,
          paymentMethod = request.paymentMethod,
          categoryId = Some(foreignCategory.id),
          startDate = request.startDate
        )
        .failed
        .futureValue
      error.getMessage must include("Category not found")
    }

    "reject a duplicate name for the same user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Gym")).futureValue
      val request = createSubscription(user.id, name = uniqueName("Pool")).futureValue

      val error = repositories.subscriptions
        .update(
          id = request.id,
          userId = user.id,
          name = existing.name,
          cadence = request.cadence,
          intervalDays = request.intervalDays,
          amountMin = request.amountMin,
          amountMax = request.amountMax,
          paymentMethod = request.paymentMethod,
          categoryId = request.categoryId,
          startDate = request.startDate
        )
        .failed
        .futureValue
      error.getMessage must include("A subscription with that name already exists")
    }

    "fail for another user's subscription" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val request = createSubscription(owner.id, name = uniqueName("Phone")).futureValue

      val error = repositories.subscriptions
        .update(
          id = request.id,
          userId = other.id,
          name = uniqueName("Hijacked"),
          cadence = request.cadence,
          intervalDays = request.intervalDays,
          amountMin = request.amountMin,
          amountMax = request.amountMax,
          paymentMethod = request.paymentMethod,
          categoryId = None,
          startDate = request.startDate
        )
        .failed
        .futureValue
      error.getMessage must include("Subscription not found")
      repositories.subscriptions.find(id = request.id, userId = owner.id).futureValue.value.name must be(request.name)
    }
  }

  "delete" should {
    "remove the subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val request = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      repositories.subscriptions.delete(id = request.id, userId = user.id).futureValue
      repositories.subscriptions.find(id = request.id, userId = user.id).futureValue must be(None)
    }

    "fail for another user's subscription" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val request = createSubscription(owner.id, name = uniqueName("Phone")).futureValue

      val error = repositories.subscriptions.delete(id = request.id, userId = other.id).failed.futureValue
      error.getMessage must include("Subscription not found")
      repositories.subscriptions.find(id = request.id, userId = owner.id).futureValue must not be empty
    }
  }

  "count" should {
    "count only the user's subscriptions" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      createSubscription(user.id, name = uniqueName("A")).futureValue
      createSubscription(user.id, name = uniqueName("B")).futureValue
      createSubscription(other.id, name = uniqueName("C")).futureValue

      repositories.subscriptions.count(user.id).futureValue must be(2L)
      repositories.subscriptions.count(other.id).futureValue must be(1L)
    }
  }

  "category deletion" should {
    "clear the subscription's category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = uniqueName("Category")).futureValue
      val request = createSubscription(user.id, name = uniqueName("Phone"), categoryId = Some(category.id)).futureValue

      repositories.categories.delete(id = category.id, userId = user.id).futureValue

      val stored = repositories.subscriptions.find(id = request.id, userId = user.id).futureValue.value
      stored.categoryId must be(None)
    }
  }
}
