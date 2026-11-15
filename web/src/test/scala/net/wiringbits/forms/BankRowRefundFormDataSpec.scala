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

class BankRowRefundFormDataSpec extends AnyWordSpec {

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
    txnType = "credit",
    expenseMerchantId = None,
    expenseCategoryId = None
  )

  private val food = GetCategories.Response.Category(UUID.randomUUID(), "Food", Instant.EPOCH, None)
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

  private val blank = BankRowRefundFormData.initial(row, None, None, notes = "")

  "initial" should {
    "resolve the seeded amount, date and prefilled selections" in {
      val form = BankRowRefundFormData.initial(row, Some(store), Some(food), notes = "a note")
      form.isValid must be(true)
      form.submitRequest must be(
        Some(
          BankRowRefundFormData.Resolved(
            amount = BigDecimal("12.50"),
            date = LocalDate.parse("2026-01-15"),
            notes = Some("a note"),
            merchant = Some(ExistingOrNewValue(MerchantName.trusted("Store"), Some(store.id))),
            category = Some(ExistingOrNewValue(CategoryName.trusted("Food"), Some(food.id)))
          )
        )
      )
    }

    "resolve blank merchant and category to no selection" in {
      val resolved = blank.submitRequest
      resolved.map(_.merchant) must be(Some(None))
      resolved.map(_.category) must be(Some(None))
    }

    "resolve blank notes to None" in {
      blank.submitRequest.map(_.notes) must be(Some(None))
      blank.copy(notes = "   ").submitRequest.map(_.notes) must be(Some(None))
    }
  }

  "submitRequest" should {
    "be None for a zero amount" in {
      val form = blank.copy(amount = blank.amount.updated(PositiveBigDecimal.validate("0")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for a non-numeric amount" in {
      val form = blank.copy(amount = blank.amount.updated(PositiveBigDecimal.validate("abc")))
      form.submitRequest must be(None)
    }

    "be None for a malformed date" in {
      val form = blank.copy(date = blank.date.updated(ExpLocalDate.validate("2026-13-45")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }
  }

  private val foodSelection = ExistingOrNewValue[CategoryName, UUID](CategoryName.trusted("Food"), Some(food.id))
  private val withFood = BankRowRefundFormData.initial(row, None, Some(food), notes = "")

  "submitRequest with invalid selections" should {
    "be None for an invalid merchant" in {
      val form = blank.copy(merchant = blank.merchant.updated(ValidationResult.Invalid("x", "bad merchant")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for an invalid category" in {
      val form = blank.copy(category = blank.category.updated(ValidationResult.Invalid("x", "bad category")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }
  }

  "selectMerchant" should {
    "fill the picked merchant's default category" in {
      val selection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Store"), Some(store.id))
      val form = blank.selectMerchant(ValidationResult.Valid("Store", Some(selection)), List(store), List(food))
      form.merchant.valueOpt must be(Some(Some(selection)))
      form.category.valueOpt must be(Some(Some(ExistingOrNewValue(CategoryName.trusted("Food"), Some(food.id)))))
      form.category.inputValue must be("Food")
    }

    "keep the chosen category when a new merchant is typed" in {
      val selection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Other"), None)
      val form = withFood.selectMerchant(ValidationResult.Valid("Other", Some(selection)), List(store), List(food))
      form.merchant.valueOpt must be(Some(Some(selection)))
      form.category.valueOpt must be(Some(Some(foodSelection)))
    }

    "keep the chosen category for a merchant without a default category" in {
      val plain = store.copy(id = UUID.randomUUID(), name = "Plain", categoryId = None)
      val selection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Plain"), Some(plain.id))
      val form = withFood.selectMerchant(ValidationResult.Valid("Plain", Some(selection)), List(plain), List(food))
      form.category.valueOpt must be(Some(Some(foodSelection)))
    }

    "keep the chosen category when the merchant's default category is not loaded" in {
      val other = store.copy(id = UUID.randomUUID(), categoryId = Some(UUID.randomUUID()))
      val selection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Store"), Some(other.id))
      val form = withFood.selectMerchant(ValidationResult.Valid("Store", Some(selection)), List(other), List(food))
      form.category.valueOpt must be(Some(Some(foodSelection)))
    }

    "keep the chosen category and block submit for an invalid merchant" in {
      val form = withFood.selectMerchant(ValidationResult.Invalid("x" * 500, "too long"), List(store), List(food))
      form.category.valueOpt must be(Some(Some(foodSelection)))
      form.submitRequest must be(None)
    }
  }
}
