package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.common.models.MerchantName
import net.wiringbits.ui.forms.{
  ExistingOrNewValue,
  ExistingSelections,
  ExpLocalDate,
  ExpLocalTime,
  ExpNotes,
  PositiveBigDecimal
}
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

class ReceiptConfirmFormDataSpec extends AnyWordSpec {

  val food = GetCategories.Response.Category(UUID.randomUUID(), "Food", Instant.EPOCH, None)
  val categories = List(food)
  val acme = GetMerchants.Response.Merchant(
    UUID.randomUUID(),
    "Acme",
    None,
    None,
    taxId = Some("TAX1"),
    reviewed = true,
    Instant.EPOCH
  )
  val other =
    GetMerchants.Response.Merchant(UUID.randomUUID(), "Other", None, None, None, reviewed = true, Instant.EPOCH)
  val merchants = List(acme, other)
  val rowId = UUID.randomUUID()
  def asOptional[T](result: ValidationResult[T]): ValidationResult[Option[T]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }
  val ruleToOther = ImportRuleMatcher.Rule(
    UUID.randomUUID(),
    ImportRuleAction.SetMerchant(other.id, categoryId = None, description = None),
    "ACME",
    amount = None
  )
  def receipt(
      amount: Option[BigDecimal] = Some(BigDecimal("12.50")),
      date: Option[LocalDate] = Some(LocalDate.parse("2026-03-04")),
      merchantName: Option[String] = Some("ACME STORE"),
      taxId: Option[String] = None
  ) = GetReceipts.Response.Receipt(
    id = UUID.randomUUID(),
    documentFilename = "r.pdf",
    documentStatus = DocumentStatus.Parsed,
    amount = amount,
    date = date,
    merchantName = merchantName,
    taxId = taxId,
    expenseId = None,
    ignoredAt = None,
    createdAt = Instant.EPOCH
  )

  def merchantId(form: ReceiptConfirmFormData): Option[UUID] = form.merchant.valueOpt.flatten.flatMap(_.id)
  def splits(form: ReceiptConfirmFormData): List[(String, String)] =
    form.splits.map(r => (r.category.inputValue, r.amount.inputValue))

  "fromReceipt" should {
    "prefill amount and date with an otherwise empty form" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(merchantName = Some("Unknown")), merchants, categories, Nil)
      form.amount.inputValue mustBe "12.50"
      form.date.inputValue mustBe "2026-03-04"
      (form.time.inputValue, form.merchant.inputValue, form.notes.inputValue) mustBe ("", "", "")
      (form.time.valueOpt, form.merchant.valueOpt, form.notes.valueOpt) mustBe (Some(None), Some(None), Some(None))
      form.splits mustBe Nil
      form.rowId mustBe None
    }

    "leave amount and date empty when the receipt has none" in {
      val form =
        ReceiptConfirmFormData.fromReceipt(
          receipt(amount = None, date = None, merchantName = None),
          merchants,
          categories,
          Nil
        )
      form.amount.inputValue mustBe ""
      form.date.inputValue mustBe ""
    }

    "prefer the merchant matching the tax id over rules" in {
      val form =
        ReceiptConfirmFormData.fromReceipt(receipt(taxId = Some("TAX1")), merchants, categories, List(ruleToOther))
      merchantId(form) mustBe Some(acme.id)
      form.merchant.inputValue mustBe "Acme"
    }

    "fall back to the winning rule for the parsed merchant name" in {
      val form =
        ReceiptConfirmFormData.fromReceipt(receipt(taxId = Some("NOPE")), merchants, categories, List(ruleToOther))
      merchantId(form) mustBe Some(other.id)
      form.merchant.inputValue mustBe "Other"
    }

    "leave the merchant blank when the rule's merchant is unknown" in {
      val rule = ImportRuleMatcher.Rule(
        UUID.randomUUID(),
        ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None),
        "ACME",
        amount = None
      )
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(rule))
      form.merchant.inputValue mustBe ""
      form.merchant.valueOpt mustBe Some(None)
    }

    "apply a rule with an amount only when the receipt total matches it as a charge" in {
      val ruleWithAmount = ruleToOther.copy(amount = Some(BigDecimal("-12.50")))
      merchantId(ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(ruleWithAmount))) mustBe
        Some(other.id)
      merchantId(
        ReceiptConfirmFormData
          .fromReceipt(receipt(amount = Some(BigDecimal("13.00"))), merchants, categories, List(ruleWithAmount))
      ) mustBe None
      merchantId(
        ReceiptConfirmFormData.fromReceipt(receipt(amount = None), merchants, categories, List(ruleWithAmount))
      ) mustBe None
    }

    val overrideRule = ruleToOther.copy(
      action = ImportRuleAction.SetMerchant(other.id, categoryId = Some(food.id), description = Some("Lunch"))
    )

    "prefill a split for the full amount with the rule's override category" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(overrideRule))
      splits(form) mustBe List((food.id.toString, "12.50"))
      form.splits.map(r => (r.category.valueOpt, r.amount.valueOpt.map(_.value))) mustBe
        List((Some(Some(food.id)), Some(BigDecimal("12.50"))))
    }

    "prefill the notes with the rule's description" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(overrideRule))
      form.notes.inputValue mustBe "Lunch"
      form.notes.valueOpt.flatten.map(_.string) mustBe Some("Lunch")
    }

    "leave splits and notes empty when the rule has no override category or description" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(ruleToOther))
      form.splits mustBe Nil
      form.notes.inputValue mustBe ""
    }

    "apply no rule effects to a tax-id match" in {
      val form =
        ReceiptConfirmFormData.fromReceipt(receipt(taxId = Some("TAX1")), merchants, categories, List(overrideRule))
      merchantId(form) mustBe Some(acme.id)
      form.splits mustBe Nil
      form.notes.inputValue mustBe ""
    }

    "apply an amount rule's effects only when the receipt total matches it as a charge" in {
      val ruleWithAmount = overrideRule.copy(amount = Some(BigDecimal("-12.50")))
      val matched = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(ruleWithAmount))
      splits(matched) mustBe List((food.id.toString, "12.50"))
      matched.notes.inputValue mustBe "Lunch"
      val unmatched =
        ReceiptConfirmFormData.fromReceipt(
          receipt(amount = Some(BigDecimal("13.00"))),
          merchants,
          categories,
          List(ruleWithAmount)
        )
      unmatched.splits mustBe Nil
      unmatched.notes.inputValue mustBe ""
    }
  }

  "submitRequest" should {
    val base = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(ruleToOther))

    "return the typed values" in {
      val withSplit = base.addSplit
      val key = withSplit.splits.head.key
      val form = withSplit
        .copy(
          time = base.time.updated(ExpLocalTime.optionalValidator("10:30")),
          notes = base.notes.updated(ExpNotes.optionalValidator("Lunch"))
        )
        .updateSplit(
          key,
          r =>
            r.copy(
              category = r.category.updated(ValidationResult.Valid(food.id.toString, Some(food.id))),
              amount = r.amount.updated(PositiveBigDecimal.validate("12.50"))
            )
        )
        .copy(rowId = Some(rowId))
      form.isValid mustBe true
      form.submitRequest mustBe Some(
        ReceiptConfirmFormData.Resolved(
          amount = BigDecimal("12.50"),
          date = LocalDate.parse("2026-03-04"),
          time = Some(LocalTime.parse("10:30")),
          merchant = Some(ExistingOrNewValue(MerchantName.trusted("Other"), Some(other.id))),
          notes = Some("Lunch"),
          splits = List((Some(food.id), BigDecimal("12.50"))),
          rowId = Some(rowId)
        )
      )
    }

    "return a typed new merchant without an id" in {
      val form = base.copy(merchant = base.merchant.updated(asOptional(ExistingSelections.newMerchant("New shop"))))
      form.submitRequest.flatMap(_.merchant).map(m => (m.value.string, m.id)) mustBe Some(("New shop", None))
    }

    "be None when the receipt has no amount" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(amount = None), merchants, categories, Nil)
      form.isValid mustBe false
      form.submitRequest mustBe None
    }

    "be None when the receipt has no date" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(date = None), merchants, categories, Nil)
      form.isValid mustBe false
      form.submitRequest mustBe None
    }

    "be None on a malformed date" in {
      val edited = base.copy(date = base.date.updated(ExpLocalDate.validate("2026-13-40")))
      edited.isValid mustBe false
      edited.submitRequest mustBe None
    }

    "be None on an invalid time" in {
      val edited = base.copy(time = base.time.updated(ExpLocalTime.optionalValidator("25:99")))
      edited.isValid mustBe false
      edited.submitRequest mustBe None
    }

    "be None on over-limit notes" in {
      val edited = base.copy(notes = base.notes.updated(ExpNotes.optionalValidator("x" * (ExpNotes.MaxLength + 1))))
      edited.isValid mustBe false
      edited.submitRequest mustBe None
    }

    "be None on an invalid merchant name" in {
      val edited =
        base.copy(merchant =
          base.merchant.updated(asOptional(ExistingSelections.newMerchant("x" * (MerchantName.MaxLength + 1))))
        )
      edited.isValid mustBe false
      edited.submitRequest mustBe None
    }

    "be None on an invalid split amount" in {
      val withSplit = base.addSplit
      val edited =
        withSplit.updateSplit(
          withSplit.splits.head.key,
          r => r.copy(amount = r.amount.updated(PositiveBigDecimal.validate("-1")))
        )
      edited.isValid mustBe false
      edited.submitRequest mustBe None
    }

    "use the edited amount" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(amount = None), merchants, categories, Nil)
      val edited = form.copy(amount = form.amount.updated(PositiveBigDecimal.validate("7.25")))
      edited.submitRequest.map(_.amount) mustBe Some(BigDecimal("7.25"))
    }
  }

  "addSplit" should {
    "use a fresh key so removing a row keeps the others' identities" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, Nil).addSplit.addSplit
      form.splits.map(_.key) mustBe List(0, 1)
      val afterRemove = form.removeSplit(0).addSplit
      afterRemove.splits.map(_.key) mustBe List(1, 2)
      afterRemove.splits.map(_.amount.name).distinct.size mustBe 2
    }
  }

  "withResolvedMerchant" should {
    val base = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(ruleToOther))
    val id = UUID.randomUUID()

    "attach the created id to a new merchant" in {
      val typed = base.copy(merchant = base.merchant.updated(asOptional(ExistingSelections.newMerchant("New shop"))))
      merchantId(typed.withResolvedMerchant(id)) mustBe Some(id)
    }

    "leave a picked merchant unchanged" in {
      base.withResolvedMerchant(id) mustBe base
    }

    "leave a blank merchant unchanged" in {
      val blank = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, Nil)
      blank.withResolvedMerchant(id) mustBe blank
    }

    "leave an invalid merchant unchanged" in {
      val invalid = base.copy(merchant =
        base.merchant.updated(asOptional(ExistingSelections.newMerchant("x" * (MerchantName.MaxLength + 1))))
      )
      invalid.withResolvedMerchant(id) mustBe invalid
    }
  }
}
