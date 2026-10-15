package net.wiringbits.actions.expenses

import net.wiringbits.api.models.expenses.DeleteExpense
import net.wiringbits.repositories.ExpensesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteExpenseAction @Inject() (expensesRepository: ExpensesRepository)(implicit ec: ExecutionContext) {

  def apply(expenseId: UUID, userId: UUID): Future[DeleteExpense.Response] = {
    for {
      existing <- expensesRepository.find(expenseId, userId)
      _ = if (existing.isEmpty) throw new RuntimeException("Expense not found")
      _ <- expensesRepository.delete(expenseId, userId)
    } yield DeleteExpense.Response()
  }
}
