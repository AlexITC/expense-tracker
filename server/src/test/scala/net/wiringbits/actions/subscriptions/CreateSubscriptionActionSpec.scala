package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{CreateSubscription, SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.common.models.{SubscriptionDescription, SubscriptionName}
import net.wiringbits.config.SubscriptionsConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.LoneElement.*
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, LocalDate}
import java.util.UUID

class CreateSubscriptionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction(maxPerUser: Int = 200)(using repositories: RepositoryComponents): CreateSubscriptionAction =
    new CreateSubscriptionAction(repositories.subscriptions, SubscriptionsConfig(maxPerUser))(using
      executionContext,
      Clock.systemUTC()
    )

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private def request(
      name: String = uniqueName("Streaming"),
      cadence: SubscriptionCadence = SubscriptionCadence.Monthly,
      intervalDays: Option[Int] = None,
      intervalMonths: Option[Int] = None,
      amountMin: Option[BigDecimal] = None,
      amountMax: Option[BigDecimal] = None,
      paymentMethod: SubscriptionPaymentMethod = SubscriptionPaymentMethod.DirectDebit,
      categoryId: Option[UUID] = None,
      merchantId: Option[UUID] = None,
      description: Option[String] = None,
      startDate: LocalDate = LocalDate.of(2024, 1, 15)
  ): CreateSubscription.Request =
    CreateSubscription.Request(
      name = SubscriptionName.trusted(name),
      cadence = cadence,
      intervalDays = intervalDays,
      intervalMonths = intervalMonths,
      amountMin = amountMin,
      amountMax = amountMax,
      paymentMethod = paymentMethod,
      categoryId = categoryId,
      merchantId = merchantId,
      // Validated like the decoder does, so surrounding whitespace is trimmed before the action sees it.
      description = description.map(SubscriptionDescription.validate(_).toOption.value),
      startDate = startDate
    )

  "apply" should {
    "store the trimmed description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val response = newAction().apply(user.id, request(description = Some("  Family plan  "))).futureValue

      repositories.subscriptions.find(id = response.id, userId = user.id).futureValue.value.description must be(
        Some("Family plan")
      )
    }

    "store a blank description as none" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val response = newAction().apply(user.id, request(description = Some("   "))).futureValue

      repositories.subscriptions.find(id = response.id, userId = user.id).futureValue.value.description must be(None)
    }

    "store a subscription with an exact amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = uniqueName("Category")).futureValue
      val req = request(
        cadence = SubscriptionCadence.Yearly,
        amountMin = Some(BigDecimal("9.99")),
        amountMax = Some(BigDecimal("9.99")),
        paymentMethod = SubscriptionPaymentMethod.CardPortal,
        categoryId = Some(category.id),
        startDate = LocalDate.of(2024, 3, 1)
      )

      val response = newAction().apply(user.id, req).futureValue

      val stored = repositories.subscriptions.find(id = response.id, userId = user.id).futureValue.value
      stored.name must be(req.name.string)
      stored.cadence must be(SubscriptionCadence.Yearly)
      stored.intervalDays must be(None)
      stored.intervalMonths must be(None)
      stored.paymentMethod must be(SubscriptionPaymentMethod.CardPortal)
      stored.categoryId must be(Some(category.id))
      val term =
        repositories.subscriptions.terms(subscriptionId = response.id, userId = user.id).futureValue.loneElement
      term.effectiveFrom must be(LocalDate.of(2024, 3, 1))
      term.effectiveTo must be(None)
      term.amountMin must be(Some(BigDecimal("9.99")))
      term.amountMax must be(Some(BigDecimal("9.99")))
    }

    "store a subscription with an amount range" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val req = request(amountMin = Some(BigDecimal("10.00")), amountMax = Some(BigDecimal("25.50")))

      val response = newAction().apply(user.id, req).futureValue

      val term =
        repositories.subscriptions.terms(subscriptionId = response.id, userId = user.id).futureValue.loneElement
      term.amountMin must be(Some(BigDecimal("10.00")))
      term.amountMax must be(Some(BigDecimal("25.50")))
    }

    "store a subscription with an unknown amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val response = newAction().apply(user.id, request()).futureValue

      val term =
        repositories.subscriptions.terms(subscriptionId = response.id, userId = user.id).futureValue.loneElement
      term.amountMin must be(None)
      term.amountMax must be(None)
    }

    "store an every-n-days cadence with its interval" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val req = request(cadence = SubscriptionCadence.EveryNDays, intervalDays = Some(45))

      val response = newAction().apply(user.id, req).futureValue

      val stored = repositories.subscriptions.find(id = response.id, userId = user.id).futureValue.value
      stored.cadence must be(SubscriptionCadence.EveryNDays)
      stored.intervalDays must be(Some(45))
    }

    "store an every-n-months cadence with its interval" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val req = request(cadence = SubscriptionCadence.EveryNMonths, intervalMonths = Some(3))

      val response = newAction().apply(user.id, req).futureValue

      val stored = repositories.subscriptions.find(id = response.id, userId = user.id).futureValue.value
      stored.cadence must be(SubscriptionCadence.EveryNMonths)
      stored.intervalDays must be(None)
      stored.intervalMonths must be(Some(3))
    }

    "reject an every-n-months cadence without interval" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val req = request(cadence = SubscriptionCadence.EveryNMonths)

      val error = newAction().apply(user.id, req).failed.futureValue
      error.getMessage must be("Interval months is required for an every-N-months cadence")
      repositories.subscriptions.count(user.id).futureValue must be(0L)
    }

    "reject a duplicate name" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val name = uniqueName("Gym")
      newAction().apply(user.id, request(name = name)).futureValue

      val error = newAction().apply(user.id, request(name = name)).failed.futureValue
      error.getMessage must include("A subscription with that name already exists")
    }

    "reject an invalid request without storing it" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val error = newAction().apply(user.id, request(cadence = SubscriptionCadence.EveryNDays)).failed.futureValue
      error.getMessage must be("Interval days is required for an every-N-days cadence")
      repositories.subscriptions.all(user.id).futureValue must be(empty)
    }

    "report an invalid request before the per-user limit" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val action = newAction(maxPerUser = 1)
      action.apply(user.id, request()).futureValue

      val error = action.apply(user.id, request(cadence = SubscriptionCadence.EveryNDays)).failed.futureValue
      error.getMessage must be("Interval days is required for an every-N-days cadence")
    }

    "reject another user's category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val category = createCategory(other.id, name = uniqueName("Category")).futureValue

      val error = newAction().apply(user.id, request(categoryId = Some(category.id))).failed.futureValue
      error.getMessage must include("Category not found")
      repositories.subscriptions.all(user.id).futureValue must be(empty)
    }

    "link an own merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id, name = uniqueName("Merchant")).futureValue

      val response = newAction().apply(user.id, request(merchantId = Some(merchant.id))).futureValue

      repositories.subscriptions.find(id = response.id, userId = user.id).futureValue.value.merchantId must be(
        Some(merchant.id)
      )
    }

    "reject another user's merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val merchant = createMerchant(other.id, name = uniqueName("Merchant")).futureValue

      val error = newAction().apply(user.id, request(merchantId = Some(merchant.id))).failed.futureValue
      error.getMessage must include("Merchant not found")
      repositories.subscriptions.all(user.id).futureValue must be(empty)
    }

    "reject a subscription once the per-user limit is reached" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val action = newAction(maxPerUser = 2)
      action.apply(user.id, request()).futureValue
      action.apply(user.id, request()).futureValue

      val error = action.apply(user.id, request()).failed.futureValue
      error.getMessage must be("Maximum number of subscriptions (2) reached")
      repositories.subscriptions.count(user.id).futureValue must be(2L)
    }

    "apply the limit per user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val action = newAction(maxPerUser = 2)
      action.apply(other.id, request()).futureValue
      action.apply(other.id, request()).futureValue

      action.apply(user.id, request()).futureValue
      repositories.subscriptions.count(user.id).futureValue must be(1L)
    }
  }
}
