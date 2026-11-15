package net.wiringbits.ui.components.inputs

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.components.core.widgets.{ExpValidatedAutocompleteInput, ValidatedTextInput}
import net.wiringbits.ui.forms.ExpPositiveAmount

import java.util.UUID

/** Experimental input instances for the validated-form-values PoC: a numeric field and select/create autocompletes,
  * built on the same stack as the existing text inputs in [[inputs]].
  */
object ExpPositiveAmountInput extends ValidatedTextInput[Option[ExpPositiveAmount]]

object ExpMerchantAutocompleteInput
    extends ExpValidatedAutocompleteInput[GetMerchants.Response.Merchant, MerchantName, UUID]

object ExpCategoryAutocompleteInput
    extends ExpValidatedAutocompleteInput[GetCategories.Response.Category, CategoryName, UUID]
