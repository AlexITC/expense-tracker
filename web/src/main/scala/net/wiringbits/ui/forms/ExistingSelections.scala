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

  /** A stored reference by name and id, e.g. the merchant an import rule points at. */
  def existingMerchant(name: String, id: UUID): ValidationResult[ExistingOrNewValue[MerchantName, UUID]] =
    withId(MerchantName.validate(name), Some(id))

  def newMerchant(text: String): ValidationResult[ExistingOrNewValue[MerchantName, UUID]] =
    withId(MerchantName.validate(text), None)

  def category(c: GetCategories.Response.Category): ValidationResult[ExistingOrNewValue[CategoryName, UUID]] =
    withId(CategoryName.validate(c.name), Some(c.id))

  def existingCategory(name: String, id: UUID): ValidationResult[ExistingOrNewValue[CategoryName, UUID]] =
    withId(CategoryName.validate(name), Some(id))

  def newCategory(text: String): ValidationResult[ExistingOrNewValue[CategoryName, UUID]] =
    withId(CategoryName.validate(text), None)

  /** The default category id of the given existing merchant, if it has one. */
  def defaultCategoryId(merchantId: Option[UUID], merchants: List[GetMerchants.Response.Merchant]): Option[UUID] =
    merchantId.flatMap(id => merchants.find(_.id == id)).flatMap(_.categoryId)

  /** The default category of the picked merchant: only an existing merchant (one with an id) whose default category is
    * still in the list yields one.
    */
  def defaultCategory(
      selection: Option[ExistingOrNewValue[MerchantName, UUID]],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): Option[GetCategories.Response.Category] =
    defaultCategoryId(selection.flatMap(_.id), merchants).flatMap(id => categories.find(_.id == id))

  private def withId[T](result: ValidationResult[T], id: Option[UUID]): ValidationResult[ExistingOrNewValue[T, UUID]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, ExistingOrNewValue(value, id))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }
}
