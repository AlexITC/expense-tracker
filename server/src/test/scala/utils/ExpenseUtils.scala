package utils

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.CreateExpenseFromRow
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.api.models.expenses.CreateExpense

import scala.concurrent.{ExecutionContext, Future}

trait ExpenseUtils {

  /** A single split covering the whole amount under a test category, since expense splits must add up to the amount. */
  def fullSplit(amount: BigDecimal)(using ec: ExecutionContext, client: ApiClient): Future[List[CreateExpense.Split]] =
    client
      .createCategory(CreateCategory.Request("Test Category"))
      .map(category => List(CreateExpense.Split(category.id, amount)))

  def fullRowSplit(
      amount: BigDecimal
  )(using ec: ExecutionContext, client: ApiClient): Future[List[CreateExpenseFromRow.Split]] =
    fullSplit(amount).map(_.map(s => CreateExpenseFromRow.Split(s.categoryId, s.amount)))
}
