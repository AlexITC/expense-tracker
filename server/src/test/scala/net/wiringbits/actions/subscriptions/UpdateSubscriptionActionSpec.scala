package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod, UpdateSubscription}
import net.wiringbits.common.models.{SubscriptionDescription, SubscriptionName}
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
      intervalMonths: Option[Int] = None,
      paymentMethod: SubscriptionPaymentMethod = SubscriptionPaymentMethod.Cash,
      categoryId: Option[UUID] = None,
      description: Option[String] = None
  ): UpdateSubscription.Request =
    UpdateSubscription.Request(
      name = SubscriptionName.trusted(name),
      cadence = cadence,
      intervalDays = intervalDays,
      intervalMonths = intervalMonths,
      paymentMethod = paymentMethod,
      categoryId = categoryId,
      // Validated like the decoder does, so surrounding whitespace is trimmed before the action sees it.
      description = description.map(SubscriptionDescription.validate(_).toOption.value)
    )

  "apply" should {
    "update every field" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = uniqueName("Category")).futureValue
      val existing = createSubscription(
        user.id,
        name = uniqueName("Phone"),
        amountMin = Some(BigDecimal("20.00")),
        amountMax = Some(BigDecimal("25.00")),
        startDate = LocalDate.of(2024, 2, 1)
      ).futureValue
      val req = request(categoryId = Some(category.id), description = Some("Family plan"))

      newAction().apply(subscriptionId = existing.id, userId = user.id, req).futureValue

      val stored = repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value
      stored.name must be(req.name.string)
      stored.cadence must be(SubscriptionCadence.EveryNDays)
      stored.intervalDays must be(Some(30))
      stored.intervalMonths must be(None)
      stored.paymentMethod must be(SubscriptionPaymentMethod.Cash)
      stored.categoryId must be(Some(category.id))
      stored.description must be(Some("Family plan"))
    }

    "edit the description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone"), description = Some("Old note")).futureValue

      newAction()
        .apply(subscriptionId = existing.id, userId = user.id, request(description = Some(" New note ")))
        .futureValue

      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value.description must be(
        Some("New note")
      )
    }

    "clear the description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone"), description = Some("Old note")).futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id, request(description = None)).futureValue

      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value.description must be(None)
    }

    "store a blank description as none" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone"), description = Some("Old note")).futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id, request(description = Some("   "))).futureValue

      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value.description must be(None)
    }

    "leave the terms unchanged" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(
        user.id,
        name = uniqueName("Phone"),
        amountMin = Some(BigDecimal("20.00")),
        amountMax = Some(BigDecimal("25.00")),
        startDate = LocalDate.of(2024, 2, 1)
      ).futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id, request()).futureValue

      repositories.subscriptions.terms(subscriptionId = existing.id, userId = user.id).futureValue must be(
        List(existing.firstTerm)
      )
    }

    "switch to an every-n-months cadence" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(
        user.id,
        name = uniqueName("Phone"),
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(45)
      ).futureValue

      newAction()
        .apply(
          subscriptionId = existing.id,
          userId = user.id,
          request(cadence = SubscriptionCadence.EveryNMonths, intervalDays = None, intervalMonths = Some(6))
        )
        .futureValue

      val stored = repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value
      stored.cadence must be(SubscriptionCadence.EveryNMonths)
      stored.intervalDays must be(None)
      stored.intervalMonths must be(Some(6))
    }

    "clear the interval months when leaving an every-n-months cadence" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(
        user.id,
        name = uniqueName("Phone"),
        cadence = SubscriptionCadence.EveryNMonths,
        intervalMonths = Some(3)
      ).futureValue

      newAction()
        .apply(
          subscriptionId = existing.id,
          userId = user.id,
          request(cadence = SubscriptionCadence.Monthly, intervalDays = None)
        )
        .futureValue

      val stored = repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value
      stored.cadence must be(SubscriptionCadence.Monthly)
      stored.intervalMonths must be(None)
    }

    "reject an every-n-months interval out of bounds" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      val error = newAction()
        .apply(
          subscriptionId = existing.id,
          userId = user.id,
          request(cadence = SubscriptionCadence.EveryNMonths, intervalDays = None, intervalMonths = Some(25))
        )
        .failed
        .futureValue
      error.getMessage must be("Interval months must be between 1 and 24")
      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue.value.cadence must be(
        existing.cadence
      )
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
        .apply(subscriptionId = existing.id, userId = user.id, request(intervalDays = None))
        .failed
        .futureValue
      error.getMessage must be("Interval days is required for an every-N-days cadence")
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
