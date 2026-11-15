package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.ui.forms.{ExpLocalDate, PositiveBigDecimal}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
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

  "fromReceipt" should {
    "prefill amount, date and parsed merchant name with an otherwise empty form" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(merchantName = Some("Unknown")), merchants, categories, Nil)
      form.amount.inputValue mustBe "12.50"
      form.date.inputValue mustBe "2026-03-04"
      (form.time, form.merchantId, form.merchantInput, form.notes) mustBe ("", "", "", "")
      form.splits mustBe Nil
      form.parsedMerchantName mustBe Some("Unknown")
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
      form.parsedMerchantName mustBe None
    }

    "prefer the merchant matching the tax id over rules" in {
      val form =
        ReceiptConfirmFormData.fromReceipt(receipt(taxId = Some("TAX1")), merchants, categories, List(ruleToOther))
      form.merchantId mustBe acme.id.toString
      form.merchantInput mustBe "Acme"
    }

    "fall back to the winning rule for the parsed merchant name" in {
      val form =
        ReceiptConfirmFormData.fromReceipt(receipt(taxId = Some("NOPE")), merchants, categories, List(ruleToOther))
      form.merchantId mustBe other.id.toString
      form.merchantInput mustBe "Other"
    }

    "leave the merchant input empty when the rule's merchant is unknown" in {
      val unknown = UUID.randomUUID()
      val rule = ImportRuleMatcher.Rule(
        UUID.randomUUID(),
        ImportRuleAction.SetMerchant(unknown, categoryId = None, description = None),
        "ACME",
        amount = None
      )
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(rule))
      form.merchantId mustBe unknown.toString
      form.merchantInput mustBe ""
    }

    "apply a rule with an amount only when the receipt total matches it as a charge" in {
      val ruleWithAmount = ruleToOther.copy(amount = Some(BigDecimal("-12.50")))
      ReceiptConfirmFormData
        .fromReceipt(receipt(), merchants, categories, List(ruleWithAmount))
        .merchantId mustBe other.id.toString
      ReceiptConfirmFormData
        .fromReceipt(receipt(amount = Some(BigDecimal("13.00"))), merchants, categories, List(ruleWithAmount))
        .merchantId mustBe ""
      ReceiptConfirmFormData
        .fromReceipt(receipt(amount = None), merchants, categories, List(ruleWithAmount))
        .merchantId mustBe ""
    }

    val overrideRule = ruleToOther.copy(
      action = ImportRuleAction.SetMerchant(other.id, categoryId = Some(food.id), description = Some("Lunch"))
    )

    "prefill a split for the full amount with the rule's override category" in {
      ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(overrideRule)).splits mustBe
        List((food.id.toString, "Food", "12.50"))
    }

    "prefill the notes with the rule's description" in {
      ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(overrideRule)).notes mustBe "Lunch"
    }

    "leave splits and notes empty when the rule has no override category or description" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(ruleToOther))
      form.splits mustBe Nil
      form.notes mustBe ""
    }

    "apply no rule effects to a tax-id match" in {
      val form =
        ReceiptConfirmFormData.fromReceipt(receipt(taxId = Some("TAX1")), merchants, categories, List(overrideRule))
      form.merchantId mustBe acme.id.toString
      form.splits mustBe Nil
      form.notes mustBe ""
    }

    "apply an amount rule's effects only when the receipt total matches it as a charge" in {
      val ruleWithAmount = overrideRule.copy(amount = Some(BigDecimal("-12.50")))
      val matched = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, List(ruleWithAmount))
      matched.splits mustBe List((food.id.toString, "Food", "12.50"))
      matched.notes mustBe "Lunch"
      val unmatched =
        ReceiptConfirmFormData.fromReceipt(
          receipt(amount = Some(BigDecimal("13.00"))),
          merchants,
          categories,
          List(ruleWithAmount)
        )
      unmatched.splits mustBe Nil
      unmatched.notes mustBe ""
    }
  }

  "submitRequest" should {
    "return the validated amount and date plus the plain values" in {
      val form = ReceiptConfirmFormData
        .fromReceipt(receipt(), merchants, categories, List(ruleToOther))
        .copy(time = "10:30", notes = "Lunch")
      form.isValid mustBe true
      form.submitRequest mustBe Some(
        ReceiptConfirmFormData.Resolved(
          amount = BigDecimal("12.50"),
          date = LocalDate.parse("2026-03-04"),
          time = "10:30",
          merchantId = other.id.toString,
          notes = "Lunch",
          splits = Nil,
          parsedMerchantName = Some("ACME STORE"),
          rowId = None
        )
      )
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
      val form = ReceiptConfirmFormData.fromReceipt(receipt(), merchants, categories, Nil)
      val edited = form.copy(date = form.date.updated(ExpLocalDate.validate("2026-13-40")))
      edited.isValid mustBe false
      edited.submitRequest mustBe None
    }

    "use the edited amount" in {
      val form = ReceiptConfirmFormData.fromReceipt(receipt(amount = None), merchants, categories, Nil)
      val edited = form.copy(amount = form.amount.updated(PositiveBigDecimal.validate("7.25")))
      edited.submitRequest.map(_.amount) mustBe Some(BigDecimal("7.25"))
    }
  }
}
