package net.wiringbits.actions.expenses

import net.wiringbits.api.models.expenses.MergeExpenses
import net.wiringbits.repositories.ExpensesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class MergeExpensesAction @Inject() (expensesRepository: ExpensesRepository)(implicit ec: ExecutionContext) {

  def apply(survivorId: UUID, loserId: UUID, userId: UUID): Future[MergeExpenses.Response] =
    expensesRepository.merge(survivorId, loserId, userId).map(_ => MergeExpenses.Response())
}
