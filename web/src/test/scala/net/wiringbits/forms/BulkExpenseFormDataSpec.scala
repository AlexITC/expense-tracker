package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExistingSelections}
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class BulkExpenseFormDataSpec extends AnyWordSpec {

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

  private val blank = BulkExpenseFormData.initial
  private val foodSelection = ExistingOrNewValue[CategoryName, UUID](CategoryName.trusted("Food"), Some(food.id))
  private val storeSelection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Store"), Some(store.id))
  private val withFood = blank.copy(category = blank.category.updated(ValidationResult.Valid("Food", foodSelection)))

  "submitRequest" should {
    "be None without a category" in {
      blank.isValid must be(false)
      blank.submitRequest must be(None)
    }

    "resolve a category-only form with no merchant and blank notes as None" in {
      withFood.copy(notes = "   ").submitRequest must be(
        Some(BulkExpenseFormData.Resolved(merchant = None, category = foodSelection, notes = None))
      )
    }

    "resolve the merchant and trimmed notes" in {
      val form = withFood.copy(
        merchant = withFood.merchant.updated(ValidationResult.Valid("Store", Some(storeSelection))),
        notes = " monthly "
      )
      form.isValid must be(true)
      form.submitRequest must be(
        Some(
          BulkExpenseFormData.Resolved(
            merchant = Some(storeSelection),
            category = foodSelection,
            notes = Some("monthly")
          )
        )
      )
    }

    "be None for an invalid typed category name" in {
      val form = blank.copy(category = blank.category.updated(ExistingSelections.newCategory("x" * 500)))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for an invalid typed merchant name" in {
      val form = withFood.copy(merchant = withFood.merchant.updated(ValidationResult.Invalid("x" * 500, "too long")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }
  }

  "selectMerchant" should {
    "fill the picked merchant's default category" in {
      val form = blank.selectMerchant(ValidationResult.Valid("Store", Some(storeSelection)), List(store), List(food))
      form.merchant.valueOpt must be(Some(Some(storeSelection)))
      form.category.valueOpt must be(Some(foodSelection))
      form.category.inputValue must be("Food")
    }

    "keep the chosen category when a new merchant is typed" in {
      val typed = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Other"), None)
      val form = withFood.selectMerchant(ValidationResult.Valid("Other", Some(typed)), List(store), List(food))
      form.merchant.valueOpt must be(Some(Some(typed)))
      form.category.valueOpt must be(Some(foodSelection))
    }

    "keep the chosen category for a picked merchant without a default category" in {
      val plain = store.copy(id = UUID.randomUUID(), name = "Plain", categoryId = None)
      val selection = ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Plain"), Some(plain.id))
      val form = withFood.selectMerchant(ValidationResult.Valid("Plain", Some(selection)), List(plain), List(food))
      form.merchant.valueOpt must be(Some(Some(selection)))
      form.category.valueOpt must be(Some(foodSelection))
    }
  }
}
