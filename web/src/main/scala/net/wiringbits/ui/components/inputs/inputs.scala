package net.wiringbits.ui.components

import net.wiringbits.common.models.{
  CategoryDescription,
  CategoryName,
  Email,
  MerchantName,
  Name,
  Password,
  SavingsGoalName,
  SubscriptionDescription,
  SubscriptionExtraChargeLabel,
  SubscriptionManualPaymentNote,
  SubscriptionName
}
import net.wiringbits.ui.components.core.widgets.ValidatedTextInput
import net.wiringbits.ui.forms.SubscriptionDescriptionValidator.optionalValidator
import net.wiringbits.ui.forms.SubscriptionManualPaymentNoteValidator.optionalValidator as manualPaymentNoteValidator

package object inputs {
  object NameInput extends ValidatedTextInput[Name]
  object EmailInput extends ValidatedTextInput[Email]
  object PasswordInput extends ValidatedTextInput[Password]
  object CategoryNameInput extends ValidatedTextInput[CategoryName]
  object CategoryDescriptionInput extends ValidatedTextInput[CategoryDescription]
  object SavingsGoalNameInput extends ValidatedTextInput[SavingsGoalName]
  object SubscriptionNameInput extends ValidatedTextInput[SubscriptionName]
  object SubscriptionDescriptionInput extends ValidatedTextInput[Option[SubscriptionDescription]]
  object SubscriptionExtraChargeLabelInput extends ValidatedTextInput[SubscriptionExtraChargeLabel]
  object SubscriptionManualPaymentNoteInput extends ValidatedTextInput[Option[SubscriptionManualPaymentNote]]
  object MerchantNameInput extends ValidatedTextInput[MerchantName]
}
