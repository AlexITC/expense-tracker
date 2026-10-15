package net.wiringbits.actions.expenses

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.repositories.ExpensesRepository
import net.wiringbits.repositories.models.{Expense, ExpenseCategorySplit}
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateExpenseAction @Inject() (
    expensesRepository: ExpensesRepository,
    validator: ExpenseValidator
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, request: CreateExpense.Request): Future[CreateExpense.Response] = {
    logger.info(
      s"Create expense: userId=$userId amount=${request.amount} date=${request.date} merchantId=${request.merchantId}"
    )
    for {
      _ <- validator.validate(
        userId,
        request.amount,
        request.notes,
        request.merchantId,
        request.splits.map(s => (s.categoryId, s.amount))
      )
      _ <- expensesRepository
        .countByMonth(userId, request.date.getYear, request.date.getMonthValue)
        .flatMap { count =>
          if (count >= 500)
            Future.failed(new RuntimeException("Maximum number of expenses for this month (500) reached"))
          else Future.unit
        }
      now = clock.instant()
      expenseId = UuidCreator.getTimeOrderedEpoch()
      expense = request
        .into[Expense.Create]
        .withFieldConst(_.id, expenseId)
        .withFieldConst(_.userId, userId)
        .withFieldConst(_.createdAt, now)
        .withFieldConst(_.updatedAt, now)
        .transform
      splits = request.splits.map { s =>
        ExpenseCategorySplit.Create(
          id = UuidCreator.getTimeOrderedEpoch(),
          userId = userId,
          expenseId = expenseId,
          categoryId = s.categoryId,
          amount = s.amount
        )
      }
      _ <- expensesRepository.create(expense, splits)
    } yield expense.transformInto[CreateExpense.Response]
  }
}
