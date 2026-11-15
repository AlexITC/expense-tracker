package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.ExistingOrNewValue
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class BulkMerchantSetupFormDataSpec extends AnyWordSpec {

  private val food =
    GetCategories.Response.Category(
      id = UUID.randomUUID(),
      name = "Food",
      createdAt = Instant.now(),
      description = None
    )

  private def merchant(categoryId: Option[UUID]) =
    GetMerchants.Response.Merchant(
      id = UUID.randomUUID(),
      name = "Store",
      notes = None,
      categoryId = categoryId,
      taxId = None,
      reviewed = false,
      createdAt = Instant.now()
    )

  private val categorySelection =
    ExistingOrNewValue[CategoryName, UUID](value = CategoryName.trusted("Food"), id = Some(food.id))

  private def pick(m: GetMerchants.Response.Merchant) =
    ValidationResult.Valid(m.name, ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted(m.name), Some(m.id)))

  private val initial = BulkMerchantSetupFormData.initial("STORE 123")

  "BulkMerchantSetupFormData" should {
    "seed the merchant from the description" in {
      initial.merchant.inputValue must be("STORE 123")
      initial.merchant.valueOpt.map(_.value.string) must be(Some("STORE 123"))
      initial.merchant.valueOpt.flatMap(_.id) must be(None)
    }

    "not be submittable when the category is missing" in {
      initial.isValid must be(false)
      initial.submitRequest must be(None)
    }

    "not be submittable when the seeded merchant is over the length limit" in {
      val longDescription = "X" * (MerchantName.MaxLength + 1)
      val form = BulkMerchantSetupFormData.initial(longDescription)
      val withCategory = form.copy(category = form.category.updated(ValidationResult.Valid("Food", categorySelection)))
      withCategory.merchant.inputValue must be(longDescription)
      withCategory.isValid must be(false)
      withCategory.submitRequest must be(None)
    }

    "be submittable when both fields are valid" in {
      val form = initial.copy(category = initial.category.updated(ValidationResult.Valid("Food", categorySelection)))
      form.isValid must be(true)
      val resolved = form.submitRequest.getOrElse(fail("expected a request"))
      resolved.merchant.value.string must be("STORE 123")
      resolved.merchant.id must be(None)
      resolved.category must be(categorySelection)
    }
  }

  "selectMerchant" should {
    "fill the picked merchant's default category" in {
      val m = merchant(Some(food.id))
      val form = initial.selectMerchant(pick(m), List(m), List(food))
      form.merchant.valueOpt.flatMap(_.id) must be(Some(m.id))
      form.category.inputValue must be("Food")
      form.category.valueOpt.flatMap(_.id) must be(Some(food.id))
      form.submitRequest.isDefined must be(true)
    }

    "leave the category alone when the merchant has no default" in {
      val m = merchant(None)
      val form = initial.selectMerchant(pick(m), List(m), List(food))
      form.merchant.valueOpt.flatMap(_.id) must be(Some(m.id))
      form.category.valueOpt must be(None)
    }

    "leave the category alone when the default category is unknown" in {
      val m = merchant(Some(UUID.randomUUID()))
      val form = initial.selectMerchant(pick(m), List(m), List(food))
      form.category.valueOpt must be(None)
    }

    "keep a chosen category when a typed merchant is entered" in {
      val withCategory =
        initial.copy(category = initial.category.updated(ValidationResult.Valid("Food", categorySelection)))
      val typed =
        ValidationResult.Valid("New", ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("New"), None))
      val form = withCategory.selectMerchant(typed, Nil, List(food))
      form.merchant.valueOpt.map(_.value.string) must be(Some("New"))
      form.category.valueOpt must be(Some(categorySelection))
    }
  }
}
