package net.wiringbits.forms

import net.wiringbits.api.models.savings.{CreateSavingsGoal, UpdateSavingsGoal}
import net.wiringbits.common.models.SavingsGoalName
import net.wiringbits.ui.forms.PositiveBigDecimal
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

case class SavingsGoalFormData(
    name: FormField[SavingsGoalName],
    targetAmount: FormField[Option[PositiveBigDecimal]]
) extends FormData[CreateSavingsGoal.Request] {
  override def fields: List[FormField[?]] = List(name, targetAmount)

  override def formValidationErrors: List[String] = List(fieldsError).flatten

  override def submitRequest: Option[CreateSavingsGoal.Request] =
    for {
      goalName <- name.valueOpt
      target <- targetAmount.valueOpt
    } yield CreateSavingsGoal.Request(name = goalName, targetAmount = target.map(_.value))

  def updateRequest: Option[UpdateSavingsGoal.Request] =
    submitRequest.map(request => UpdateSavingsGoal.Request(name = request.name, targetAmount = request.targetAmount))
}

object SavingsGoalFormData {

  private val NameLabel = "Goal name"
  private val TargetLabel = "Target amount (optional)"

  def initial(name: Option[String], targetAmount: Option[BigDecimal]): SavingsGoalFormData =
    SavingsGoalFormData(
      name = new FormField(
        label = NameLabel,
        name = "name",
        required = true,
        value = name.map(SavingsGoalName.validate)
      ),
      targetAmount = new FormField(
        label = TargetLabel,
        name = "targetAmount",
        required = false,
        value = Some(PositiveBigDecimal.optionalValidator(targetAmount.map(_.toString).getOrElse("")))
      )
    )
}
