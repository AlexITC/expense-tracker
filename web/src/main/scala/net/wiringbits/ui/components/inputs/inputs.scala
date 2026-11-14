package net.wiringbits.ui.components

import net.wiringbits.common.models.{CategoryDescription, CategoryName, Email, Name, Password, SavingsGoalName}
import net.wiringbits.ui.components.core.widgets.ValidatedTextInput

package object inputs {
  object NameInput extends ValidatedTextInput[Name]
  object EmailInput extends ValidatedTextInput[Email]
  object PasswordInput extends ValidatedTextInput[Password]
  object CategoryNameInput extends ValidatedTextInput[CategoryName]
  object CategoryDescriptionInput extends ValidatedTextInput[CategoryDescription]
  object SavingsGoalNameInput extends ValidatedTextInput[SavingsGoalName]
}
