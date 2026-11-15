package net.wiringbits.ui.components.inputs

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.components.core.widgets.{ExpValidatedAutocompleteInput, ValidatedTextInput}
import net.wiringbits.ui.forms.{
  ExistingOrNewValue,
  ExpLocalDate,
  ExpRuleAmount,
  ExpRuleDescription,
  ExpRulePattern,
  PositiveBigDecimal
}

import java.util.UUID

/** Experimental input instances for the validated-form-values PoC: validated numeric/text fields and select/create
  * autocompletes, built on the same stack as the existing text inputs in [[inputs]].
  */
object PositiveBigDecimalInput extends ValidatedTextInput[Option[PositiveBigDecimal]]

object AmountInput extends ValidatedTextInput[PositiveBigDecimal]

object DateInput extends ValidatedTextInput[ExpLocalDate]

object RulePatternInput extends ValidatedTextInput[ExpRulePattern]

object RuleAmountInput extends ValidatedTextInput[Option[ExpRuleAmount]]

object RuleDescriptionInput extends ValidatedTextInput[Option[ExpRuleDescription]]

object MerchantAutocompleteInput
    extends ExpValidatedAutocompleteInput[
      GetMerchants.Response.Merchant,
      MerchantName,
      UUID,
      ExistingOrNewValue[MerchantName, UUID]
    ](optionLabel = _.name, optionId = _.id)

object CategoryAutocompleteInput
    extends ExpValidatedAutocompleteInput[
      GetCategories.Response.Category,
      CategoryName,
      UUID,
      ExistingOrNewValue[CategoryName, UUID]
    ](optionLabel = _.name, optionId = _.id)

object OptionalCategoryAutocompleteInput
    extends ExpValidatedAutocompleteInput[
      GetCategories.Response.Category,
      CategoryName,
      UUID,
      Option[ExistingOrNewValue[CategoryName, UUID]]
    ](optionLabel = _.name, optionId = _.id)

object OptionalMerchantAutocompleteInput
    extends ExpValidatedAutocompleteInput[
      GetMerchants.Response.Merchant,
      MerchantName,
      UUID,
      Option[ExistingOrNewValue[MerchantName, UUID]]
    ](optionLabel = _.name, optionId = _.id)
