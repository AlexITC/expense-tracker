package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.{
  CreateSubscription,
  GetSubscriptions,
  SubscriptionCadence,
  SubscriptionPaymentMethod,
  SubscriptionTerm,
  UpdateSubscription
}
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{MerchantName, SubscriptionDescription, SubscriptionName}
import net.wiringbits.ui.forms.{
  ExistingOrNewValue,
  ExistingSelections,
  ExpLocalDate,
  ExpOptionalId,
  SubscriptionAmount,
  SubscriptionDescriptionValidator,
  SubscriptionIntervalDays,
  SubscriptionIntervalMonths
}
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class SubscriptionFormDataSpec extends AnyWordSpec {

  private val categoryId = UUID.randomUUID()

  private val merchant = GetMerchants.Response.Merchant(
    id = UUID.randomUUID(),
    name = s"Merchant ${UUID.randomUUID()}",
    notes = None,
    categoryId = None,
    taxId = None,
    reviewed = true,
    createdAt = Instant.parse("2026-01-01T00:00:00Z")
  )

  private def asOptional[T](result: ValidationResult[T]): ValidationResult[Option[T]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }

  private val initialForm = CreateSubscriptionFormData.initial(nameLabel = "Name")

  extension (form: SubscriptionFormData) {
    private def withName(raw: String): SubscriptionFormData =
      form.copy(name = form.name.updated(SubscriptionName.validate(raw)))
    private def withInterval(raw: String): SubscriptionFormData =
      form.copy(intervalDays = form.intervalDays.updated(SubscriptionIntervalDays.validate(raw)))
    private def withIntervalMonths(raw: String): SubscriptionFormData =
      form.copy(intervalMonths = form.intervalMonths.updated(SubscriptionIntervalMonths.validate(raw)))
    private def withCategory(raw: String): SubscriptionFormData =
      form.copy(categoryId = form.categoryId.updated(ExpOptionalId.validate(raw)))
    private def withDescription(raw: String): SubscriptionFormData =
      form.copy(description = form.description.updated(SubscriptionDescriptionValidator.optionalValidator(raw)))
    private def withMerchant(result: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]]) =
      form.copy(merchant = form.merchant.updated(result))
    // The request the page sends when no merchant is selected.
    private def sentRequest: Option[UpdateSubscription.Request] = form.submitRequest.map(_.request(merchantId = None))
  }

  extension (form: CreateSubscriptionFormData) {
    private def withDetails(f: SubscriptionFormData => SubscriptionFormData): CreateSubscriptionFormData =
      form.copy(details = f(form.details))
    private def withName(raw: String): CreateSubscriptionFormData = form.withDetails(_.withName(raw))
    private def withInterval(raw: String): CreateSubscriptionFormData = form.withDetails(_.withInterval(raw))
    private def withIntervalMonths(raw: String): CreateSubscriptionFormData =
      form.withDetails(_.withIntervalMonths(raw))
    private def withCategory(raw: String): CreateSubscriptionFormData = form.withDetails(_.withCategory(raw))
    private def withDescription(raw: String): CreateSubscriptionFormData = form.withDetails(_.withDescription(raw))
    private def withCadence(cadence: SubscriptionCadence): CreateSubscriptionFormData =
      form.withDetails(_.copy(cadence = cadence))
    private def withAmounts(f: SubscriptionAmountFields => SubscriptionAmountFields): CreateSubscriptionFormData =
      form.copy(amounts = f(form.amounts))
    private def withAmountMode(mode: AmountMode): CreateSubscriptionFormData = form.withAmounts(_.copy(mode = mode))
    private def withExact(raw: String): CreateSubscriptionFormData =
      form.withAmounts(a => a.copy(exact = a.exact.updated(SubscriptionAmount.optionalValidator(raw))))
    private def withMin(raw: String): CreateSubscriptionFormData =
      form.withAmounts(a => a.copy(min = a.min.updated(SubscriptionAmount.optionalValidator(raw))))
    private def withMax(raw: String): CreateSubscriptionFormData =
      form.withAmounts(a => a.copy(max = a.max.updated(SubscriptionAmount.optionalValidator(raw))))
    private def withStartDate(raw: String): CreateSubscriptionFormData =
      form.copy(startDate = form.startDate.updated(ExpLocalDate.validate(raw)))
    private def withMerchant(
        result: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]]
    ): CreateSubscriptionFormData = form.withDetails(_.withMerchant(result))
    // The request the page sends when no merchant is selected.
    private def sentRequest: Option[CreateSubscription.Request] = form.submitRequest.map(_.request(merchantId = None))
  }

  private val validForm = initialForm.withName("Streaming").withExact("12.50").withStartDate("2026-01-15")

  private val expectedRequest = CreateSubscription.Request(
    name = SubscriptionName.trusted("Streaming"),
    cadence = SubscriptionCadence.Monthly,
    intervalDays = None,
    intervalMonths = None,
    amountMin = Some(BigDecimal("12.50")),
    amountMax = Some(BigDecimal("12.50")),
    paymentMethod = SubscriptionPaymentMethod.DirectDebit,
    categoryId = None,
    merchantId = None,
    description = None,
    startDate = LocalDate.parse("2026-01-15")
  )

  private val rangeForm = validForm.withAmountMode(AmountMode.Range).withMin("10").withMax("25.00")

  private val everyNDaysForm = validForm.withCadence(SubscriptionCadence.EveryNDays)

  private val everyNMonthsForm = validForm.withCadence(SubscriptionCadence.EveryNMonths)

  private def assertBlocked(form: CreateSubscriptionFormData) = {
    form.isValid must be(false)
    form.formValidationErrors must not be empty
    form.sentRequest must be(None)
  }

  "fields" should {
    "list the inputs of a monthly cadence with an exact amount" in {
      initialForm.fields.map(_.name) must be(
        List(
          "subscription-create-name",
          "subscription-create-merchant",
          "subscription-create-category",
          "subscription-create-description",
          "subscription-create-exact-amount",
          "subscription-create-start-date"
        )
      )
    }

    "list the interval only for an every-N-days cadence" in {
      everyNDaysForm.fields.map(_.name) must contain("subscription-create-interval")
      validForm.withCadence(SubscriptionCadence.Yearly).fields.map(_.name) must not contain
        "subscription-create-interval"
      everyNMonthsForm.fields.map(_.name) must not contain "subscription-create-interval"
    }

    "list the interval months only for an every-N-months cadence" in {
      everyNMonthsForm.fields.map(_.name) must contain("subscription-create-interval-months")
      everyNDaysForm.fields.map(_.name) must not contain "subscription-create-interval-months"
      validForm.fields.map(_.name) must not contain "subscription-create-interval-months"
    }
  }

  "submitRequest" should {
    "return a request with min equal to max for an exact amount" in {
      validForm.isValid must be(true)
      validForm.sentRequest must be(Some(expectedRequest))
    }

    "return a request with both amounts for a range" in {
      rangeForm.sentRequest must be(
        Some(expectedRequest.copy(amountMin = Some(BigDecimal("10")), amountMax = Some(BigDecimal("25.00"))))
      )
    }

    "return a request without amounts when the exact amount is blank" in {
      validForm.withExact(" ").sentRequest must be(Some(expectedRequest.copy(amountMin = None, amountMax = None)))
    }

    "include the interval for an every-N-days cadence" in {
      everyNDaysForm.withInterval(" 14 ").sentRequest must be(
        Some(expectedRequest.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = Some(14)))
      )
    }

    "include the interval months for an every-N-months cadence" in {
      everyNMonthsForm.withIntervalMonths(" 3 ").sentRequest must be(
        Some(expectedRequest.copy(cadence = SubscriptionCadence.EveryNMonths, intervalMonths = Some(3)))
      )
    }

    "ignore the interval input for other cadences, even when invalid" in {
      validForm.withInterval("abc").sentRequest must be(Some(expectedRequest))
    }

    "ignore the interval months input for other cadences, even when invalid" in {
      validForm.withIntervalMonths("abc").sentRequest must be(Some(expectedRequest))
      everyNDaysForm.withInterval("14").withIntervalMonths("3").sentRequest must be(
        Some(expectedRequest.copy(cadence = SubscriptionCadence.EveryNDays, intervalDays = Some(14)))
      )
    }

    "include the picked category" in {
      validForm.withCategory(categoryId.toString).sentRequest must be(
        Some(expectedRequest.copy(categoryId = Some(categoryId)))
      )
    }

    "return None when the name is untouched" in {
      assertBlocked(validForm.withDetails(_.copy(name = initialForm.details.name)))
    }

    "return None when the start date is untouched" in {
      assertBlocked(validForm.copy(startDate = initialForm.startDate))
    }

    "return None when an every-N-days cadence has no interval" in {
      assertBlocked(everyNDaysForm)
    }

    "return None when an every-N-months cadence has no interval" in {
      assertBlocked(everyNMonthsForm)
    }

    "return None when the interval months is out of bounds" in {
      assertBlocked(everyNMonthsForm.withIntervalMonths("0"))
      assertBlocked(everyNMonthsForm.withIntervalMonths("25"))
    }

    "return None when the exact amount is not a number" in {
      assertBlocked(validForm.withExact("abc"))
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

    "include the trimmed description" in {
      validForm.withDescription("  Family plan ").sentRequest must be(
        Some(expectedRequest.copy(description = Some(SubscriptionDescription.trusted("Family plan"))))
      )
    }

    "send no description when it is blank" in {
      val form = validForm.withDescription("   ")
      form.isValid must be(true)
      form.sentRequest must be(Some(expectedRequest))
    }

    "return None when the description is over 500 characters" in {
      assertBlocked(validForm.withDescription("a" * 501))
    }

    "carry no merchant selection by default" in {
      validForm.submitRequest.map(_.details.merchant) must be(Some(None))
    }

    "carry a picked merchant for the page to resolve" in {
      val resolved = validForm.withMerchant(asOptional(ExistingSelections.merchant(merchant))).submitRequest
      resolved.map(_.details.merchant) must be(
        Some(Some(ExistingOrNewValue(value = MerchantName.trusted(merchant.name), id = Some(merchant.id))))
      )
      resolved.map(_.request(merchantId = Some(merchant.id))) must be(
        Some(expectedRequest.copy(merchantId = Some(merchant.id)))
      )
    }

    "carry a typed new merchant without an id" in {
      validForm
        .withMerchant(asOptional(ExistingSelections.newMerchant("Corner shop")))
        .submitRequest
        .map(
          _.details.merchant
        ) must be(Some(Some(ExistingOrNewValue(value = MerchantName.trusted("Corner shop"), id = None))))
    }

    "carry the created merchant's id once the typed merchant is replaced by it" in {
      val typed = validForm.withMerchant(asOptional(ExistingSelections.newMerchant(merchant.name)))
      typed.withDetails(_.withExistingMerchant(merchant)).submitRequest.map(_.details.merchant) must be(
        Some(Some(ExistingOrNewValue(value = MerchantName.trusted(merchant.name), id = Some(merchant.id))))
      )
    }

    "return None when the typed merchant name is invalid" in {
      assertBlocked(validForm.withMerchant(asOptional(ExistingSelections.newMerchant("a" * 1000))))
    }
  }

  "existingMerchantId" should {
    "be None when no merchant is selected" in {
      validForm.details.existingMerchantId must be(None)
    }

    "be the picked merchant's id" in {
      validForm.withMerchant(asOptional(ExistingSelections.merchant(merchant))).details.existingMerchantId must be(
        Some(merchant.id)
      )
    }

    "be None for a typed new merchant" in {
      validForm
        .withMerchant(asOptional(ExistingSelections.newMerchant("Corner shop")))
        .details
        .existingMerchantId must be(None)
    }
  }

  "withSuggestedStartDate" should {
    "fill an untouched start date so the form becomes submittable" in {
      val form = initialForm.withName("Streaming").withSuggestedStartDate(LocalDate.of(2025, 2, 3))
      form.startDate.valueOpt.map(_.value) must be(Some(LocalDate.of(2025, 2, 3)))
      form.sentRequest.map(_.startDate) must be(Some(LocalDate.of(2025, 2, 3)))
    }

    "replace a typed start date" in {
      validForm.withSuggestedStartDate(LocalDate.of(2025, 2, 3)).sentRequest must be(
        Some(expectedRequest.copy(startDate = LocalDate.of(2025, 2, 3)))
      )
    }
  }

  "fromSubscription" should {
    val subscription = GetSubscriptions.Response.Subscription(
      id = UUID.randomUUID(),
      name = s"Gym ${UUID.randomUUID()}",
      cadence = SubscriptionCadence.EveryNDays,
      intervalDays = Some(28),
      intervalMonths = None,
      paymentMethod = SubscriptionPaymentMethod.CardPortal,
      categoryId = Some(categoryId),
      merchantId = Some(merchant.id),
      description = Some("Family plan"),
      terms = List(
        SubscriptionTerm(
          id = UUID.randomUUID(),
          effectiveFrom = LocalDate.parse("2026-02-01"),
          effectiveTo = None,
          amountMin = Some(BigDecimal("40.00")),
          amountMax = Some(BigDecimal("40.00"))
        )
      ),
      extraCharges = List.empty,
      createdAt = Instant.parse("2026-02-01T10:00:00Z")
    )

    val editForm = SubscriptionFormData.fromSubscription(
      nameLabel = "Name",
      subscription = subscription,
      merchants = List(merchant)
    )

    val expectedUpdate = UpdateSubscription.Request(
      name = SubscriptionName.trusted(subscription.name),
      cadence = subscription.cadence,
      intervalDays = subscription.intervalDays,
      intervalMonths = subscription.intervalMonths,
      paymentMethod = subscription.paymentMethod,
      categoryId = subscription.categoryId,
      merchantId = None,
      description = Some(SubscriptionDescription.trusted("Family plan"))
    )

    "list only the subscription's own inputs" in {
      editForm.fields.map(_.name) must be(
        List(
          "subscription-edit-name",
          "subscription-edit-interval",
          "subscription-edit-merchant",
          "subscription-edit-category",
          "subscription-edit-description"
        )
      )
    }

    "round-trip into an update request" in {
      editForm.categoryId.inputValue must be(categoryId.toString)
      editForm.description.inputValue must be("Family plan")
      editForm.isValid must be(true)
      editForm.sentRequest must be(Some(expectedUpdate))
    }

    "apply edits to the update request" in {
      val edited =
        editForm.withName("Pool").copy(cadence = SubscriptionCadence.Yearly).withCategory("").withDescription("")
      edited.sentRequest must be(
        Some(
          expectedUpdate.copy(
            name = SubscriptionName.trusted("Pool"),
            cadence = SubscriptionCadence.Yearly,
            intervalDays = None,
            categoryId = None,
            description = None
          )
        )
      )
    }

    "block an every-N-days cadence without a valid interval" in {
      val form = editForm.withInterval("0")
      form.isValid must be(false)
      form.sentRequest must be(None)
    }

    "seed and round-trip an every-N-months subscription" in {
      val quarterly = subscription.copy(
        cadence = SubscriptionCadence.EveryNMonths,
        intervalDays = None,
        intervalMonths = Some(3)
      )
      val form = SubscriptionFormData.fromSubscription(
        nameLabel = "Name",
        subscription = quarterly,
        merchants = List(merchant)
      )
      form.fields.map(_.name) must be(
        List(
          "subscription-edit-name",
          "subscription-edit-interval-months",
          "subscription-edit-merchant",
          "subscription-edit-category",
          "subscription-edit-description"
        )
      )
      form.intervalMonths.inputValue must be("3")
      form.sentRequest must be(
        Some(
          expectedUpdate.copy(
            cadence = SubscriptionCadence.EveryNMonths,
            intervalDays = None,
            intervalMonths = Some(3)
          )
        )
      )
    }

    "block an every-N-months cadence without a valid interval" in {
      val form = editForm.copy(cadence = SubscriptionCadence.EveryNMonths).withIntervalMonths("30")
      form.isValid must be(false)
      form.sentRequest must be(None)
    }

    "seed the linked merchant by name and id" in {
      editForm.merchant.inputValue must be(merchant.name)
      editForm.submitRequest.map(_.merchant) must be(
        Some(Some(ExistingOrNewValue(value = MerchantName.trusted(merchant.name), id = Some(merchant.id))))
      )
    }

    "unlink the merchant when it is cleared" in {
      editForm.withMerchant(ValidationResult.Valid("", None)).submitRequest.map(_.merchant) must be(Some(None))
    }

    "block a linked merchant missing from the merchant list" in {
      val form = SubscriptionFormData.fromSubscription(nameLabel = "Name", subscription = subscription, merchants = Nil)
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "seed no merchant for an unlinked subscription" in {
      SubscriptionFormData
        .fromSubscription(nameLabel = "Name", subscription = subscription.copy(merchantId = None), merchants = Nil)
        .submitRequest
        .map(_.merchant) must be(Some(None))
    }

    "block an invalid name" in {
      editForm.withName(" ").sentRequest must be(None)
    }
  }
}
