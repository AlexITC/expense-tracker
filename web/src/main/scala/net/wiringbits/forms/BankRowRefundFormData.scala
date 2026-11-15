package net.wiringbits.forms

import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExistingSelections, ExpLocalDate, ExpNotes, PositiveBigDecimal}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate
import java.util.UUID

/** The "new refund" form of a bank statement row: a required positive amount and date, optional validated notes, and
  * optional merchant/category selections. Resolving or creating the selections stays a side effect of the component.
  */
case class BankRowRefundFormData(
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    notes: FormField[Option[ExpNotes]],
    merchant: FormField[Option[ExistingOrNewValue[MerchantName, UUID]]],
    category: FormField[Option[ExistingOrNewValue[CategoryName, UUID]]]
) extends FormData[BankRowRefundFormData.Resolved] {
  override def fields: List[FormField[?]] = List(amount, date, notes, merchant, category)

  override def submitRequest: Option[BankRowRefundFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
      n <- notes.valueOpt
      m <- merchant.valueOpt
      c <- category.valueOpt
    } yield BankRowRefundFormData.Resolved(
      amount = a.value,
      date = d.value,
      notes = n.map(_.string),
      merchant = m,
      category = c
    )

  /** Updates the merchant and, when an existing merchant with a known default category is picked, fills the category
    * with it.
    */
  def selectMerchant(
      value: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): BankRowRefundFormData = {
    val defaultCategory = ExistingSelections.defaultCategory(value.toOption.flatten, merchants, categories)
    copy(
      merchant = merchant.updated(value),
      category = defaultCategory.fold(category)(c =>
        category.updated(BankRowRefundFormData.asOptional(ExistingSelections.category(c)))
      )
    )
  }
}

object BankRowRefundFormData {
  case class Resolved(
      amount: BigDecimal,
      date: LocalDate,
      notes: Option[String],
      merchant: Option[ExistingOrNewValue[MerchantName, UUID]],
      category: Option[ExistingOrNewValue[CategoryName, UUID]]
  )

  private def asOptional[T](result: ValidationResult[T]): ValidationResult[Option[T]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }

  private def blank[T]: ValidationResult[Option[T]] = ValidationResult.Valid("", None)

  def initial(
      row: GetBankStatement.StatementRowData,
      matchedMerchant: Option[GetMerchants.Response.Merchant],
      initialCategory: Option[GetCategories.Response.Category],
      notes: String
  ): BankRowRefundFormData =
    BankRowRefundFormData(
      amount = new FormField(
        label = "Amount",
        name = "amount",
        required = true,
        value = Some(PositiveBigDecimal.validate(row.amount.abs.toString))
      ),
      date = new FormField(
        label = "Date",
        name = "date",
        required = true,
        value = Some(ExpLocalDate.validate(row.date.toString))
      ),
      notes = new FormField(
        label = "Notes",
        name = "notes",
        required = false,
        value = Some(ExpNotes.optionalValidator(notes))
      ),
      merchant = new FormField(
        label = "Merchant (optional)",
        name = "merchant",
        required = false,
        value = Some(
          matchedMerchant.fold(blank[ExistingOrNewValue[MerchantName, UUID]])(m =>
            asOptional(ExistingSelections.merchant(m))
          )
        )
      ),
      category = new FormField(
        label = "Category (optional)",
        name = "category",
        required = false,
        value = Some(
          initialCategory.fold(blank[ExistingOrNewValue[CategoryName, UUID]])(c =>
            asOptional(ExistingSelections.category(c))
          )
        )
      )
    )
}
