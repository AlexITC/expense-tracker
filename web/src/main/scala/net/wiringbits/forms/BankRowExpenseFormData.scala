package net.wiringbits.forms

import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExistingSelections, ExpLocalDate, PositiveBigDecimal}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate
import java.util.UUID

/** The "new expense" form of a bank statement row: a required positive amount, date and category, free-text notes, an
  * optional merchant, plus the rule/receipt choices. Resolving or creating the selections stays a side effect of the
  * component.
  */
case class BankRowExpenseFormData(
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    notes: String,
    merchant: FormField[Option[ExistingOrNewValue[MerchantName, UUID]]],
    category: FormField[ExistingOrNewValue[CategoryName, UUID]],
    rulePattern: String,
    categoryRuleOptIn: Boolean,
    receiptId: Option[UUID]
) extends FormData[BankRowExpenseFormData.Resolved] {
  override def fields: List[FormField[?]] = List(amount, date, merchant, category)

  override def submitRequest: Option[BankRowExpenseFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
      m <- merchant.valueOpt
      c <- category.valueOpt
    } yield BankRowExpenseFormData.Resolved(
      amount = a.value,
      date = d.value,
      notes = Some(notes).filter(_.nonEmpty),
      merchant = m,
      category = c,
      rulePattern = rulePattern,
      categoryRuleOptIn = categoryRuleOptIn,
      receiptId = receiptId
    )

  /** The picked merchant's id, if an existing merchant is selected. */
  def merchantIdOpt: Option[UUID] = merchant.valueOpt.flatten.flatMap(_.id)

  /** Updates the merchant and, when an existing merchant with a known default category is picked, fills the category
    * with it.
    */
  def selectMerchant(
      value: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): BankRowExpenseFormData = {
    val defaultCategory = ExistingSelections.defaultCategory(value.toOption.flatten, merchants, categories)
    copy(
      merchant = merchant.updated(value),
      category = defaultCategory.fold(category)(c => category.updated(ExistingSelections.category(c)))
    )
  }

  /** Whether the merchant/category fields still hold the values a blank form starts with (no rule/AI prefill): the
    * merchant is the row description with no picked id, and the category is empty.
    */
  def isPristine(row: GetBankStatement.StatementRowData): Boolean =
    merchantIdOpt.isEmpty && merchant.inputValue == row.description && category.inputValue.isEmpty

  /** Applies a rule/AI suggestion: each given entity replaces its field, a missing one leaves the field as is. */
  def applySuggestion(
      suggestedMerchant: Option[GetMerchants.Response.Merchant],
      suggestedCategory: Option[GetCategories.Response.Category]
  ): BankRowExpenseFormData =
    copy(
      merchant = suggestedMerchant.fold(merchant)(m =>
        merchant.updated(BankRowExpenseFormData.asOptional(ExistingSelections.merchant(m)))
      ),
      category = suggestedCategory.fold(category)(c => category.updated(ExistingSelections.category(c)))
    )

  /** Takes the receipt's merchant; the category follows it, so without a default category the previous one is cleared.
    */
  def useReceiptMerchant(
      receiptMerchant: GetMerchants.Response.Merchant,
      categoryId: Option[UUID],
      categories: List[GetCategories.Response.Category]
  ): BankRowExpenseFormData = {
    val withMerchant =
      copy(merchant = merchant.updated(BankRowExpenseFormData.asOptional(ExistingSelections.merchant(receiptMerchant))))
    categoryId match {
      case None =>
        withMerchant.copy(category = new FormField(category.label, category.name, category.required))
      case Some(cid) =>
        categories
          .find(_.id == cid)
          .fold(withMerchant)(c => withMerchant.copy(category = category.updated(ExistingSelections.category(c))))
    }
  }

  /** Attaches the id of a merchant created by a submit, so a retry after a later failure reuses it. */
  def withResolvedMerchant(id: UUID): BankRowExpenseFormData =
    merchant.value match {
      case Some(ValidationResult.Valid(input, Some(selection))) if selection.id.isEmpty =>
        copy(merchant = merchant.updated(ValidationResult.Valid(input, Some(selection.copy(id = Some(id))))))
      case _ => this
    }
}

object BankRowExpenseFormData {
  case class Resolved(
      amount: BigDecimal,
      date: LocalDate,
      notes: Option[String],
      merchant: Option[ExistingOrNewValue[MerchantName, UUID]],
      category: ExistingOrNewValue[CategoryName, UUID],
      rulePattern: String,
      categoryRuleOptIn: Boolean,
      receiptId: Option[UUID]
  )

  private def asOptional[T](result: ValidationResult[T]): ValidationResult[Option[T]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }

  /** @param merchantInput
    *   the merchant text used when no merchant is prefilled; blank means no merchant
    */
  def initial(
      row: GetBankStatement.StatementRowData,
      initialMerchant: Option[GetMerchants.Response.Merchant],
      merchantInput: String,
      initialCategory: Option[GetCategories.Response.Category],
      notes: String
  ): BankRowExpenseFormData = {
    val merchantSeed: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]] =
      initialMerchant match {
        case Some(m) => asOptional(ExistingSelections.merchant(m))
        case None if merchantInput.trim.isEmpty => ValidationResult.Valid(merchantInput, None)
        case None => asOptional(ExistingSelections.newMerchant(merchantInput))
      }
    BankRowExpenseFormData(
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
      notes = notes,
      merchant = new FormField(
        label = "Merchant",
        name = "merchant",
        required = false,
        value = Some(merchantSeed)
      ),
      category = new FormField(
        label = "Category",
        name = "category",
        required = true,
        value = initialCategory.map(ExistingSelections.category)
      ),
      rulePattern = row.description,
      categoryRuleOptIn = false,
      receiptId = None
    )
  }
}
