package net.wiringbits.actions.savings

import net.wiringbits.api.models.savings.GetSavingsGoals
import net.wiringbits.repositories.SavingsRepository
import net.wiringbits.repositories.models.SavingsGoal

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetSavingsGoalsAction @Inject() (savingsRepository: SavingsRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetSavingsGoals.Response] = {
    for {
      goals <- savingsRepository.allGoals(userId)
    } yield GetSavingsGoals.Response(goals.map(toGoal))
  }

  private def toGoal(pair: (SavingsGoal, BigDecimal)): GetSavingsGoals.Response.Goal = {
    val (goal, balance) = pair
    val progress = goal.targetAmount.map(target => balance / target)
    GetSavingsGoals.Response.Goal(goal.id, goal.name, goal.targetAmount, balance, progress, goal.createdAt)
  }
}
