package net.wiringbits.actions.savings

import net.wiringbits.api.models.savings.DeleteSavingsGoal
import net.wiringbits.repositories.SavingsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteSavingsGoalAction @Inject() (savingsRepository: SavingsRepository)(implicit ec: ExecutionContext) {

  def apply(goalId: UUID, userId: UUID): Future[DeleteSavingsGoal.Response] = {
    for {
      existing <- savingsRepository.findGoal(id = goalId, userId = userId)
      _ = if (existing.isEmpty) throw new RuntimeException("Savings goal not found")
      _ <- savingsRepository.deleteGoal(id = goalId, userId = userId)
    } yield DeleteSavingsGoal.Response()
  }
}
