package net.wiringbits.ui.components.inputs

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.components.core.widgets.{ExpValidatedAutocompleteInput, ValidatedTextInput}
import net.wiringbits.ui.forms.{ExpRuleAmount, ExpRuleDescription, ExpRulePattern, PositiveBigDecimal}

import java.util.UUID

/** Experimental input instances for the validated-form-values PoC: validated numeric/text fields and select/create
  * autocompletes, built on the same stack as the existing text inputs in [[inputs]].
  */
object PositiveBigDecimalInput extends ValidatedTextInput[Option[PositiveBigDecimal]]

object RulePatternInput extends ValidatedTextInput[ExpRulePattern]

object RuleAmountInput extends ValidatedTextInput[Option[ExpRuleAmount]]

object RuleDescriptionInput extends ValidatedTextInput[Option[ExpRuleDescription]]

object MerchantAutocompleteInput
    extends ExpValidatedAutocompleteInput[GetMerchants.Response.Merchant, MerchantName, UUID] {
  override protected def optionLabel(option: GetMerchants.Response.Merchant): String = option.name
  override protected def optionId(option: GetMerchants.Response.Merchant): UUID = option.id
}

object CategoryAutocompleteInput
    extends ExpValidatedAutocompleteInput[GetCategories.Response.Category, CategoryName, UUID] {
  override protected def optionLabel(option: GetCategories.Response.Category): String = option.name
  override protected def optionId(option: GetCategories.Response.Category): UUID = option.id
}
