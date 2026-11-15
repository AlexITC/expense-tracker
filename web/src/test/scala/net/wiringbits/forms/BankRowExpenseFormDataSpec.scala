package net.wiringbits.forms

import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExpLocalDate, PositiveBigDecimal}
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankRowExpenseFormDataSpec extends AnyWordSpec {

  private val row = GetBankStatement.StatementRowData(
    id = UUID.randomUUID(),
    index = 0,
    date = LocalDate.parse("2026-01-15"),
    amount = BigDecimal("-12.50"),
    description = "STORE 123",
    manuallyEdited = false,
    confirmedAt = None,
    expenseId = None,
    refundId = None,
    ignoredAt = None,
    txnType = "debit",
    expenseMerchantId = None,
    expenseCategoryId = None
  )

  private val food = GetCategories.Response.Category(UUID.randomUUID(), "Food", Instant.EPOCH, None)
  private val fuel = GetCategories.Response.Category(UUID.randomUUID(), "Fuel", Instant.EPOCH, None)
  private val categories = List(food, fuel)
  private val store =
    GetMerchants.Response.Merchant(
      UUID.randomUUID(),
      "Store",
      None,
      Some(food.id),
      None,
      reviewed = true,
      Instant.EPOCH
    )
  private val plain = store.copy(id = UUID.randomUUID(), name = "Plain", categoryId = None)
  private val merchants = List(store, plain)

  private val storeSelection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Store"), Some(store.id))
  private val foodSelection = ExistingOrNewValue[CategoryName, UUID](CategoryName.trusted("Food"), Some(food.id))
  private val fuelSelection = ExistingOrNewValue[CategoryName, UUID](CategoryName.trusted("Fuel"), Some(fuel.id))

  // The untouched blank form: the merchant text is the row description, no category.
  private val blank = BankRowExpenseFormData.initial(row, None, row.description, None, notes = "a note")
  private val withFood = blank.copy(category = blank.category.updated(ValidationResult.Valid("Food", foodSelection)))

  "submitRequest" should {
    "resolve a valid form" in {
      val form = BankRowExpenseFormData.initial(row, Some(store), "", Some(food), notes = "a note")
      form.isValid must be(true)
      form.submitRequest must be(
        Some(
          BankRowExpenseFormData.Resolved(
            amount = BigDecimal("12.50"),
            date = LocalDate.parse("2026-01-15"),
            notes = Some("a note"),
            merchant = Some(storeSelection),
            category = foodSelection,
            rulePattern = row.description,
            categoryRuleOptIn = false,
            receiptId = None
          )
        )
      )
    }

    "be None when the category is missing" in {
      blank.isValid must be(false)
      blank.submitRequest must be(None)
    }

    "be None for a bad amount" in {
      val form = withFood.copy(amount = withFood.amount.updated(PositiveBigDecimal.validate("abc")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for a bad date" in {
      val form = withFood.copy(date = withFood.date.updated(ExpLocalDate.validate("2026-13-45")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "resolve a new merchant typed from the row description" in {
      withFood.submitRequest.flatMap(_.merchant) must be(
        Some(ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted(row.description), None))
      )
    }

    "resolve a blank optional merchant to no merchant" in {
      val form = BankRowExpenseFormData.initial(row, None, "", Some(food), notes = "")
      form.isValid must be(true)
      form.submitRequest.map(_.merchant) must be(Some(None))
      form.submitRequest.map(_.notes) must be(Some(None))
    }

    "not truncate an over-limit merchant seed and block submit" in {
      val longText = "x" * (MerchantName.MaxLength + 1)
      val form = BankRowExpenseFormData.initial(row, None, longText, Some(food), notes = "")
      form.merchant.inputValue must be(longText)
      form.isValid must be(false)
      form.submitRequest must be(None)
    }
  }

  "selectMerchant" should {
    "fill the picked merchant's default category" in {
      val form = blank.selectMerchant(ValidationResult.Valid("Store", Some(storeSelection)), merchants, categories)
      form.merchant.valueOpt must be(Some(Some(storeSelection)))
      form.category.valueOpt must be(Some(foodSelection))
    }

    "keep the chosen category for a merchant without a default category" in {
      val fuelForm = blank.copy(category = blank.category.updated(ValidationResult.Valid("Fuel", fuelSelection)))
      val selection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Plain"), Some(plain.id))
      val form = fuelForm.selectMerchant(ValidationResult.Valid("Plain", Some(selection)), merchants, categories)
      form.category.valueOpt must be(Some(fuelSelection))
    }

    "keep the chosen category when the merchant is cleared" in {
      val form = withFood.selectMerchant(ValidationResult.Valid("", None), merchants, categories)
      form.merchant.valueOpt must be(Some(None))
      form.category.valueOpt must be(Some(foodSelection))
    }

    "keep the chosen category when a new merchant is typed" in {
      val typed = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Other"), None)
      val form = withFood.selectMerchant(ValidationResult.Valid("Other", Some(typed)), merchants, categories)
      form.merchant.valueOpt must be(Some(Some(typed)))
      form.category.valueOpt must be(Some(foodSelection))
    }
  }

  "isPristine" should {
    "be true for the untouched blank form" in {
      blank.isPristine(row) must be(true)
    }

    "be false once a merchant is selected, even with the row description as its text" in {
      val picked = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted(row.description), Some(plain.id))
      blank
        .selectMerchant(ValidationResult.Valid(row.description, Some(picked)), Nil, Nil)
        .isPristine(row) must be(false)
    }

    "be false once the merchant text is edited" in {
      val typed = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("STORE"), None)
      blank.selectMerchant(ValidationResult.Valid("STORE", Some(typed)), Nil, Nil).isPristine(row) must be(false)
    }

    "be false once a category is set" in {
      withFood.isPristine(row) must be(false)
    }
  }

  "applySuggestion" should {
    "fill the merchant and category of a pristine form" in {
      val form = blank.applySuggestion(Some(store), Some(fuel))
      form.merchant.valueOpt must be(Some(Some(storeSelection)))
      form.category.valueOpt must be(Some(fuelSelection))
    }

    "keep fields the suggestion does not cover" in {
      val form = blank.applySuggestion(None, Some(fuel))
      form.merchant.inputValue must be(row.description)
      form.category.valueOpt must be(Some(fuelSelection))
    }
  }

  "useReceiptMerchant" should {
    "set the merchant and its default category" in {
      val form = blank.useReceiptMerchant(store, store.categoryId, categories)
      form.merchant.valueOpt must be(Some(Some(storeSelection)))
      form.category.valueOpt must be(Some(foodSelection))
    }

    "clear the category when the receipt merchant has no default" in {
      val form = withFood.useReceiptMerchant(plain, None, categories)
      form.merchantIdOpt must be(Some(plain.id))
      form.category.valueOpt must be(None)
      form.category.inputValue must be("")
      form.submitRequest must be(None)
    }

    "keep the category when the receipt merchant's default category is not loaded" in {
      val form = withFood.useReceiptMerchant(store, Some(UUID.randomUUID()), categories)
      form.merchant.valueOpt must be(Some(Some(storeSelection)))
      form.category.valueOpt must be(Some(foodSelection))
    }
  }

  "withResolvedMerchant" should {
    "attach the created merchant's id to a typed merchant" in {
      val id = UUID.randomUUID()
      withFood.withResolvedMerchant(id).submitRequest.flatMap(_.merchant) must be(
        Some(ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted(row.description), Some(id)))
      )
    }

    "leave a blank merchant unchanged" in {
      val form = BankRowExpenseFormData.initial(row, None, "", Some(food), notes = "")
      form.withResolvedMerchant(UUID.randomUUID()) must be(form)
    }

    "leave an invalid merchant unchanged" in {
      val form = withFood.copy(merchant = withFood.merchant.updated(ValidationResult.Invalid("x", "bad merchant")))
      form.withResolvedMerchant(UUID.randomUUID()) must be(form)
    }

    "keep the id of an existing merchant" in {
      val form = BankRowExpenseFormData.initial(row, Some(store), "", Some(food), notes = "")
      form.withResolvedMerchant(UUID.randomUUID()) must be(form)
    }
  }
}
