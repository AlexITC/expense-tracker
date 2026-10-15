package net.wiringbits.actions.savings

import net.wiringbits.api.models.savings.UpdateSavingsGoal
import net.wiringbits.repositories.SavingsRepository
import net.wiringbits.validations.ValidateSavingsGoalNameIsAvailable

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateSavingsGoalAction @Inject() (savingsRepository: SavingsRepository)(implicit ec: ExecutionContext) {

  def apply(goalId: UUID, userId: UUID, request: UpdateSavingsGoal.Request): Future[UpdateSavingsGoal.Response] = {
    for {
      _ <- validate(goalId, userId, request)
      _ <- savingsRepository.updateGoal(goalId, userId, request.name, request.targetAmount)
    } yield UpdateSavingsGoal.Response()
  }

  private def validate(goalId: UUID, userId: UUID, request: UpdateSavingsGoal.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 100) Future.failed(new RuntimeException("Name must be 100 characters or less"))
    else if (request.targetAmount.exists(_ <= 0))
      Future.failed(new RuntimeException("Target amount must be greater than zero"))
    else
      for {
        existing <- savingsRepository.findGoal(goalId, userId)
        _ = if (existing.isEmpty) throw new RuntimeException("Savings goal not found")
        _ <- ValidateSavingsGoalNameIsAvailable(savingsRepository, userId, request.name, excludeId = Some(goalId))
      } yield ()
  }
}
