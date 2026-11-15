package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExistingSelections, ExpNotes}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.util.UUID

/** The bulk expense creation form: an optional merchant, a required category and optional validated notes, applied to
  * every selected row. Resolving or creating the selections stays a side effect of the component.
  */
case class BulkExpenseFormData(
    merchant: FormField[Option[ExistingOrNewValue[MerchantName, UUID]]],
    category: FormField[ExistingOrNewValue[CategoryName, UUID]],
    notes: FormField[Option[ExpNotes]]
) extends FormData[BulkExpenseFormData.Resolved] {
  override def fields: List[FormField[?]] = List(merchant, category, notes)

  override def submitRequest: Option[BulkExpenseFormData.Resolved] =
    for {
      m <- merchant.valueOpt
      c <- category.valueOpt
      n <- notes.valueOpt
    } yield BulkExpenseFormData.Resolved(
      merchant = m,
      category = c,
      notes = n.map(_.string)
    )

  /** Updates the merchant and, when an existing merchant with a known default category is picked, fills the category
    * with it.
    */
  def selectMerchant(
      value: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): BulkExpenseFormData = {
    val defaultCategory = ExistingSelections.defaultCategory(value.toOption.flatten, merchants, categories)
    copy(
      merchant = merchant.updated(value),
      category = defaultCategory.fold(category)(c => category.updated(ExistingSelections.category(c)))
    )
  }
}

object BulkExpenseFormData {
  case class Resolved(
      merchant: Option[ExistingOrNewValue[MerchantName, UUID]],
      category: ExistingOrNewValue[CategoryName, UUID],
      notes: Option[String]
  )

  val initial: BulkExpenseFormData =
    BulkExpenseFormData(
      merchant = new FormField(
        label = "Merchant (optional)",
        name = "merchant",
        required = false,
        value = Some(ValidationResult.Valid("", None))
      ),
      category = new FormField(label = "Category", name = "category", required = true),
      notes = new FormField(
        label = "Notes (optional, applied to all)",
        name = "notes",
        required = false,
        value = Some(ExpNotes.optionalValidator(""))
      )
    )
}
