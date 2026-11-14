package net.wiringbits.forms

import net.wiringbits.api.models.savings.{CreateSavingsGoal, UpdateSavingsGoal}
import net.wiringbits.common.models.SavingsGoalName
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import scala.util.Try

case class SavingsGoalFormData(
    name: FormField[SavingsGoalName],
    targetAmount: String
) extends FormData[CreateSavingsGoal.Request] {
  override def fields: List[FormField[?]] = List(name)

  // The target is optional free text: blank means "no target", a non-numeric or non-positive value blocks submission.
  // It stays outside the validated-primitive stack because it is a BigDecimal, not a WrappedString.
  def parsedTarget: Option[Either[String, BigDecimal]] = {
    val trimmed = targetAmount.trim
    if (trimmed.isEmpty) None
    else
      Try(BigDecimal(trimmed)).toOption match {
        case Some(value) if value > 0 => Some(Right(value))
        case Some(_) => Some(Left("Target amount must be greater than zero"))
        case None => Some(Left("Invalid target amount"))
      }
  }

  override def formValidationErrors: List[String] =
    List(fieldsError).flatten ++ parsedTarget.collect { case Left(error) => error }

  override def submitRequest: Option[CreateSavingsGoal.Request] =
    for {
      goalName <- name.valueOpt
      target <- parsedTarget match {
        case None => Some(None)
        case Some(Right(value)) => Some(Some(value))
        case Some(Left(_)) => None
      }
    } yield CreateSavingsGoal.Request(name = goalName, targetAmount = target)

  def updateRequest: Option[UpdateSavingsGoal.Request] =
    submitRequest.map(request => UpdateSavingsGoal.Request(name = request.name, targetAmount = request.targetAmount))
}

object SavingsGoalFormData {

  private val NameLabel = "Goal name"

  def initial(name: Option[SavingsGoalName], targetAmount: String): SavingsGoalFormData =
    SavingsGoalFormData(
      name = new FormField(
        label = NameLabel,
        name = "name",
        required = true,
        value = name.map(x => ValidationResult.Valid(x.string, x))
      ),
      targetAmount = targetAmount
    )
}
