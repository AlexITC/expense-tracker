package net.wiringbits.actions.expenses

import net.wiringbits.api.models.expenses.{MergeDetailsSource, MergeExpenses}
import net.wiringbits.repositories.ExpensesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class MergeExpensesAction @Inject() (expensesRepository: ExpensesRepository)(implicit ec: ExecutionContext) {

  def apply(
      survivorId: UUID,
      loserId: UUID,
      userId: UUID,
      keepDetailsFrom: MergeDetailsSource
  ): Future[MergeExpenses.Response] =
    expensesRepository
      .merge(survivorId = survivorId, loserId = loserId, userId = userId, keepDetailsFrom = keepDetailsFrom)
      .map(_ => MergeExpenses.Response())
}
