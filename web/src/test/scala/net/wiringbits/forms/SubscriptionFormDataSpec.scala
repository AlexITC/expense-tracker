package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.{
  CreateSubscription,
  GetSubscriptions,
  SubscriptionCadence,
  SubscriptionPaymentMethod,
  UpdateSubscription
}
import net.wiringbits.common.models.SubscriptionName
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class SubscriptionFormDataSpec extends AnyWordSpec {

  private val categoryId = UUID.fromString("00000000-0000-0000-0000-000000000001")

  private val initialForm = SubscriptionFormData.initial(nameLabel = "Name")

  private val validForm = initialForm.copy(
    name = initialForm.name.updated(SubscriptionName.validate("Streaming")),
    exactAmount = "12.50",
    startDate = "2026-01-15"
  )

  private val expectedRequest = CreateSubscription.Request(
    name = SubscriptionName.trusted("Streaming"),
    cadence = SubscriptionCadence.Monthly,
    intervalDays = None,
    amountMin = Some(BigDecimal("12.50")),
    amountMax = Some(BigDecimal("12.50")),
    paymentMethod = SubscriptionPaymentMethod.DirectDebit,
    categoryId = None,
    startDate = LocalDate.parse("2026-01-15")
  )

  private val rangeForm = validForm.copy(amountMode = AmountMode.Range, minAmount = "10", maxAmount = "25.00")

  private def assertBlocked(form: SubscriptionFormData) = {
    form.formValidationErrors must not be empty
    form.submitRequest must be(None)
    form.updateRequest must be(None)
  }

  "fields" should {
    "return the expected fields" in {
      initialForm.fields.map(_.name) must be(List("name"))
    }
  }

  "submitRequest" should {
    "return a request with min equal to max for an exact amount" in {
      validForm.formValidationErrors must be(empty)
      validForm.submitRequest must be(Some(expectedRequest))
    }

    "return a request with both amounts for a range" in {
      rangeForm.submitRequest must be(
        Some(expectedRequest.copy(amountMin = Some(BigDecimal("10")), amountMax = Some(BigDecimal("25.00"))))
      )
    }

    "return a request without amounts when the exact amount is blank" in {
      validForm.copy(exactAmount = " ").submitRequest must be(
        Some(expectedRequest.copy(amountMin = None, amountMax = None))
      )
    }

    "return a request without amounts when both range amounts are blank" in {
      rangeForm.copy(minAmount = "", maxAmount = "").submitRequest must be(
        Some(expectedRequest.copy(amountMin = None, amountMax = None))
      )
    }

    "include the interval for an every-N-days cadence" in {
      validForm.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = " 14 ").submitRequest must be(
        Some(expectedRequest.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = Some(14)))
      )
    }

    "ignore the interval input for other cadences" in {
      validForm.copy(intervalDays = "14").submitRequest must be(Some(expectedRequest))
    }

    "include the picked category" in {
      validForm.copy(categoryInput = "Entertainment", categoryId = Some(categoryId)).submitRequest must be(
        Some(expectedRequest.copy(categoryId = Some(categoryId)))
      )
    }

    "return None when the name is untouched" in {
      assertBlocked(validForm.copy(name = initialForm.name))
    }

    "return None when the exact amount is not a number" in {
      assertBlocked(validForm.copy(exactAmount = "abc"))
    }

    "return None when a range amount is not a number" in {
      assertBlocked(rangeForm.copy(maxAmount = "lots"))
    }

    "return None when only one range amount is set" in {
      assertBlocked(rangeForm.copy(maxAmount = ""))
    }

    "return None when the minimum is greater than the maximum" in {
      assertBlocked(rangeForm.copy(minAmount = "30"))
    }

    "return None when the amount has too many decimals" in {
      assertBlocked(validForm.copy(exactAmount = "1.234"))
    }

    "return None when the amount is not positive" in {
      assertBlocked(validForm.copy(exactAmount = "0"))
    }

    "return None when an every-N-days cadence has no interval" in {
      assertBlocked(validForm.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = ""))
    }

    "return None when the interval is not a whole number" in {
      assertBlocked(validForm.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = "1.5"))
    }

    "return None when the interval is not positive" in {
      assertBlocked(validForm.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = "0"))
    }

    "return None when the start date is blank" in {
      assertBlocked(validForm.copy(startDate = ""))
    }

    "return None when the start date is not in YYYY-MM-DD format" in {
      assertBlocked(validForm.copy(startDate = "15/01/2026"))
    }

    "return None when category text is typed but no category is picked" in {
      val form = validForm.copy(categoryInput = "Entertainment", categoryId = None)
      form.formValidationErrors must contain("Pick a category from the list")
      assertBlocked(form)
    }
  }

  "updateRequest" should {
    "mirror submitRequest" in {
      rangeForm.updateRequest must be(
        Some(
          UpdateSubscription.Request(
            name = SubscriptionName.trusted("Streaming"),
            cadence = SubscriptionCadence.Monthly,
            intervalDays = None,
            amountMin = Some(BigDecimal("10")),
            amountMax = Some(BigDecimal("25.00")),
            paymentMethod = SubscriptionPaymentMethod.DirectDebit,
            categoryId = None,
            startDate = LocalDate.parse("2026-01-15")
          )
        )
      )
    }
  }

  "fromSubscription" should {
    def subscription(amountMin: Option[BigDecimal], amountMax: Option[BigDecimal]) =
      GetSubscriptions.Response.Subscription(
        id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        name = "Gym",
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(28),
        amountMin = amountMin,
        amountMax = amountMax,
        paymentMethod = SubscriptionPaymentMethod.CardPortal,
        categoryId = Some(categoryId),
        startDate = LocalDate.parse("2026-02-01"),
        createdAt = Instant.parse("2026-02-01T10:00:00Z")
      )

    def roundTrip(s: GetSubscriptions.Response.Subscription) =
      SubscriptionFormData.fromSubscription(nameLabel = "Name", subscription = s, categoryName = Some("Health"))

    def expectedUpdate(s: GetSubscriptions.Response.Subscription) = UpdateSubscription.Request(
      name = SubscriptionName.trusted(s.name),
      cadence = s.cadence,
      intervalDays = s.intervalDays,
      amountMin = s.amountMin,
      amountMax = s.amountMax,
      paymentMethod = s.paymentMethod,
      categoryId = s.categoryId,
      startDate = s.startDate
    )

    "pick exact mode when min equals max and round-trip" in {
      val s = subscription(Some(BigDecimal("40.00")), Some(BigDecimal("40.00")))
      val form = roundTrip(s)
      form.amountMode must be(AmountMode.Exact)
      form.exactAmount must be("40.00")
      form.categoryInput must be("Health")
      form.updateRequest must be(Some(expectedUpdate(s)))
    }

    "pick range mode when min differs from max and round-trip" in {
      val s = subscription(Some(BigDecimal("30.00")), Some(BigDecimal("45.00")))
      val form = roundTrip(s)
      form.amountMode must be(AmountMode.Range)
      form.updateRequest must be(Some(expectedUpdate(s)))
    }

    "round-trip an unknown amount" in {
      val s = subscription(None, None)
      roundTrip(s).updateRequest must be(Some(expectedUpdate(s)))
    }
  }
}
