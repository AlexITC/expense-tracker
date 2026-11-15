package net.wiringbits.forms

import net.wiringbits.api.models.merchants.ImportRuleActionKind
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.ExpEntityRef
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

/** The import-rule form as a standard [[FormData]].
  *
  * The two select/create fields live in the form as `FormField`s exactly like the text fields of a login form, built by
  * [[BankRowRuleFormData.initial]] and held in a single `useState`. Each holds an `Option[ExpEntityRef[T]]`: the
  * validated name plus the id of the picked row, or `None` when blank. The free-text members (pattern, amount,
  * description) and the action kind are plain fields on the model, the same way `SignInFormData` keeps its captcha
  * beside its `FormField`s. `submitRequest` yields the validated selections; resolving/creating the selected merchant
  * or category is a side effect the component runs, so it stays out of the pure form model.
  */
case class BankRowRuleFormData(
    actionKind: ImportRuleActionKind,
    pattern: String,
    amountText: String,
    description: String,
    merchant: FormField[Option[ExpEntityRef[MerchantName]]],
    category: FormField[Option[ExpEntityRef[CategoryName]]]
) extends FormData[BankRowRuleFormData.Inputs] {

  // Only the selection fields relevant to the chosen action are validated; the text members are checked by the
  // component with the same server-side checks it already used.
  override def fields: List[FormField[?]] = actionKind match {
    case ImportRuleActionKind.SetMerchant => List(merchant, category)
    case ImportRuleActionKind.SetCategory => List(category)
    case ImportRuleActionKind.Ignore => Nil
  }

  override def submitRequest: Option[BankRowRuleFormData.Inputs] =
    for {
      merchantSelection <- merchant.valueOpt
      categorySelection <- category.valueOpt
    } yield BankRowRuleFormData.Inputs(
      actionKind = actionKind,
      pattern = pattern,
      amountText = amountText,
      description = description,
      merchant = merchantSelection,
      category = categorySelection
    )
}

object BankRowRuleFormData {

  case class Inputs(
      actionKind: ImportRuleActionKind,
      pattern: String,
      amountText: String,
      description: String,
      merchant: Option[ExpEntityRef[MerchantName]],
      category: Option[ExpEntityRef[CategoryName]]
  )

  private def emptySelection[T](label: String, name: String): FormField[Option[ExpEntityRef[T]]] =
    new FormField(
      label = label,
      name = name,
      required = false,
      value = Some(ValidationResult.Valid("", None))
    )

  def initial(pattern: String, amountText: String, description: String): BankRowRuleFormData =
    BankRowRuleFormData(
      actionKind = ImportRuleActionKind.SetMerchant,
      pattern = pattern,
      amountText = amountText,
      description = description,
      merchant = emptySelection(label = "Merchant", name = "merchant"),
      category = emptySelection(label = "Category", name = "category")
    )
}
