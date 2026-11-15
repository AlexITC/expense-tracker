package net.wiringbits.ui.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.webapp.common.validators.ValidationResult

import java.util.UUID

/** Seeds for select/create fields: a selection built from an existing entity (validated name + its id) or from raw text
  * (validated name, no id). Names are always validated, never trusted, so an over-limit seed shows as invalid.
  */
object ExistingSelections {
  def merchant(m: GetMerchants.Response.Merchant): ValidationResult[ExistingOrNewValue[MerchantName, UUID]] =
    withId(MerchantName.validate(m.name), Some(m.id))

  def newMerchant(text: String): ValidationResult[ExistingOrNewValue[MerchantName, UUID]] =
    withId(MerchantName.validate(text), None)

  def category(c: GetCategories.Response.Category): ValidationResult[ExistingOrNewValue[CategoryName, UUID]] =
    withId(CategoryName.validate(c.name), Some(c.id))

  def newCategory(text: String): ValidationResult[ExistingOrNewValue[CategoryName, UUID]] =
    withId(CategoryName.validate(text), None)

  private def withId[T](result: ValidationResult[T], id: Option[UUID]): ValidationResult[ExistingOrNewValue[T, UUID]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, ExistingOrNewValue(value, id))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }
}
