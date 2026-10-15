package net.wiringbits.actions.expenses

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.expenses.UpdateExpense
import net.wiringbits.repositories.ExpensesRepository
import net.wiringbits.repositories.models.ExpenseCategorySplit

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateExpenseAction @Inject() (
    expensesRepository: ExpensesRepository,
    validator: ExpenseValidator
)(implicit ec: ExecutionContext) {

  def apply(expenseId: UUID, userId: UUID, request: UpdateExpense.Request): Future[UpdateExpense.Response] = {
    for {
      existing <- expensesRepository.find(expenseId, userId)
      _ = if (existing.isEmpty) throw new RuntimeException("Expense not found")
      _ <- validator.validate(
        userId,
        request.amount,
        request.notes,
        request.merchantId,
        request.splits.map(s => (s.categoryId, s.amount))
      )
      splits = request.splits.map { s =>
        ExpenseCategorySplit.Create(
          id = UuidCreator.getTimeOrderedEpoch(),
          userId = userId,
          expenseId = expenseId,
          categoryId = s.categoryId,
          amount = s.amount
        )
      }
      _ <- expensesRepository.update(
        expenseId,
        userId,
        request.amount,
        request.date,
        request.time,
        request.merchantId,
        request.notes,
        splits
      )
    } yield UpdateExpense.Response()
  }
}
