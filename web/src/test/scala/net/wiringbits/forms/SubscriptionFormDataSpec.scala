package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.{
  CreateSubscription,
  GetSubscriptions,
  SubscriptionCadence,
  SubscriptionPaymentMethod,
  UpdateSubscription
}
import net.wiringbits.common.models.SubscriptionName
import net.wiringbits.ui.forms.{ExpLocalDate, ExpOptionalId, SubscriptionAmount, SubscriptionIntervalDays}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class SubscriptionFormDataSpec extends AnyWordSpec {

  private val categoryId = UUID.randomUUID()

  private val initialForm = SubscriptionFormData.initial(nameLabel = "Name")

  extension (form: SubscriptionFormData) {
    private def withName(raw: String) = form.copy(name = form.name.updated(SubscriptionName.validate(raw)))
    private def withExact(raw: String) =
      form.copy(exactAmount = form.exactAmount.updated(SubscriptionAmount.optionalValidator(raw)))
    private def withMin(raw: String) =
      form.copy(minAmount = form.minAmount.updated(SubscriptionAmount.optionalValidator(raw)))
    private def withMax(raw: String) =
      form.copy(maxAmount = form.maxAmount.updated(SubscriptionAmount.optionalValidator(raw)))
    private def withInterval(raw: String) =
      form.copy(intervalDays = form.intervalDays.updated(SubscriptionIntervalDays.validate(raw)))
    private def withStartDate(raw: String) =
      form.copy(startDate = form.startDate.updated(ExpLocalDate.validate(raw)))
    private def withCategory(raw: String) = form.copy(categoryId = form.categoryId.updated(ExpOptionalId.validate(raw)))
  }

  private val validForm = initialForm.withName("Streaming").withExact("12.50").withStartDate("2026-01-15")

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

  private val rangeForm = validForm.copy(amountMode = AmountMode.Range).withMin("10").withMax("25.00")

  private val everyNDaysForm = validForm.copy(cadence = SubscriptionCadence.EveryNDays)

  private def assertBlocked(form: SubscriptionFormData) = {
    form.isValid must be(false)
    form.formValidationErrors must not be empty
    form.submitRequest must be(None)
    form.updateRequest must be(None)
  }

  "fields" should {
    "list the inputs of a monthly cadence with an exact amount" in {
      initialForm.fields.map(_.name) must be(
        List(
          "subscription-create-name",
          "subscription-create-exact-amount",
          "subscription-create-category",
          "subscription-create-start-date"
        )
      )
    }

    "list the interval only for an every-N-days cadence" in {
      everyNDaysForm.fields.map(_.name) must contain("subscription-create-interval")
      validForm.copy(cadence = SubscriptionCadence.Yearly).fields.map(_.name) must not contain
        "subscription-create-interval"
    }

    "list both range amounts for a range" in {
      rangeForm.fields.map(
        _.name
      ) must contain allOf ("subscription-create-min-amount", "subscription-create-max-amount")
      rangeForm.fields.map(_.name) must not contain "subscription-create-exact-amount"
    }
  }

  "submitRequest" should {
    "return a request with min equal to max for an exact amount" in {
      validForm.isValid must be(true)
      validForm.submitRequest must be(Some(expectedRequest))
    }

    "return a request with both amounts for a range" in {
      rangeForm.submitRequest must be(
        Some(expectedRequest.copy(amountMin = Some(BigDecimal("10")), amountMax = Some(BigDecimal("25.00"))))
      )
    }

    "return a request without amounts when the exact amount is blank" in {
      validForm.withExact(" ").submitRequest must be(Some(expectedRequest.copy(amountMin = None, amountMax = None)))
    }

    "return a request without amounts when both range amounts are blank" in {
      rangeForm.withMin("").withMax("").submitRequest must be(
        Some(expectedRequest.copy(amountMin = None, amountMax = None))
      )
    }

    "include the interval for an every-N-days cadence" in {
      everyNDaysForm.withInterval(" 14 ").submitRequest must be(
        Some(expectedRequest.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = Some(14)))
      )
    }

    "ignore the interval input for other cadences, even when invalid" in {
      validForm.withInterval("abc").submitRequest must be(Some(expectedRequest))
    }

    "ignore the hidden range amounts in exact mode" in {
      validForm.withMin("abc").submitRequest must be(Some(expectedRequest))
    }

    "include the picked category" in {
      validForm.withCategory(categoryId.toString).submitRequest must be(
        Some(expectedRequest.copy(categoryId = Some(categoryId)))
      )
    }

    "return None when the name is untouched" in {
      assertBlocked(validForm.copy(name = initialForm.name))
    }

    "return None when the start date is untouched" in {
      assertBlocked(validForm.copy(startDate = initialForm.startDate))
    }

    "return None when an every-N-days cadence has no interval" in {
      assertBlocked(everyNDaysForm)
    }

    "return None when the exact amount is not a number" in {
      assertBlocked(validForm.withExact("abc"))
    }

    "return None when a range amount is not a number" in {
      assertBlocked(rangeForm.withMax("lots"))
    }

    "return None when only one range amount is set" in {
      assertBlocked(rangeForm.withMax(""))
    }

    "return None when the minimum is greater than the maximum" in {
      val form = rangeForm.withMin("30")
      form.formValidationErrors must contain("Minimum amount must not be greater than the maximum amount")
      assertBlocked(form)
    }

    "return None when the interval is not positive" in {
      assertBlocked(everyNDaysForm.withInterval("0"))
    }

    "return None when the start date is not in YYYY-MM-DD format" in {
      assertBlocked(validForm.withStartDate("15/01/2026"))
    }

    "return None when the category is not a valid id" in {
      assertBlocked(validForm.withCategory("not-an-id"))
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
        id = UUID.randomUUID(),
        name = s"Gym ${UUID.randomUUID()}",
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
      SubscriptionFormData.fromSubscription(nameLabel = "Name", subscription = s)

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
      form.exactAmount.inputValue must be("40.00")
      form.categoryId.inputValue must be(categoryId.toString)
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
