package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExistingSelections}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.util.UUID

/** The bulk merchant setup row: assigns a merchant (existing or new) and its category to an unmatched description.
  * Resolving or creating the selections stays a side effect of the component.
  */
case class BulkMerchantSetupFormData(
    merchant: FormField[ExistingOrNewValue[MerchantName, UUID]],
    category: FormField[ExistingOrNewValue[CategoryName, UUID]]
) extends FormData[BulkMerchantSetupFormData.Resolved] {
  override def fields: List[FormField[?]] = List(merchant, category)

  override def submitRequest: Option[BulkMerchantSetupFormData.Resolved] =
    for {
      m <- merchant.valueOpt
      c <- category.valueOpt
    } yield BulkMerchantSetupFormData.Resolved(merchant = m, category = c)

  /** Updates the merchant and, when an existing merchant with a known default category is picked, fills the category
    * with it.
    */
  def selectMerchant(
      value: ValidationResult[ExistingOrNewValue[MerchantName, UUID]],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): BulkMerchantSetupFormData = {
    val defaultCategory = ExistingSelections.defaultCategory(value.toOption, merchants, categories)
    copy(
      merchant = merchant.updated(value),
      category = defaultCategory.fold(category)(c => category.updated(ExistingSelections.category(c)))
    )
  }
}

object BulkMerchantSetupFormData {
  case class Resolved(
      merchant: ExistingOrNewValue[MerchantName, UUID],
      category: ExistingOrNewValue[CategoryName, UUID]
  )

  def initial(description: String): BulkMerchantSetupFormData =
    BulkMerchantSetupFormData(
      merchant = new FormField(
        label = "Merchant",
        name = "merchant",
        required = true,
        value = Some(ExistingSelections.newMerchant(description))
      ),
      category = new FormField(label = "Category", name = "category", required = true)
    )
}
