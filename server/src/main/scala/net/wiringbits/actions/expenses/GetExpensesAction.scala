package net.wiringbits.actions.expenses

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.repositories.{BankStatementRepository, ExpensesRepository, MerchantsRepository, ReceiptRepository}

import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetExpensesAction @Inject() (
    expensesRepository: ExpensesRepository,
    merchantsRepository: MerchantsRepository,
    receiptRepository: ReceiptRepository,
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext) {

  def apply(
      userId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate],
      unlinkedOnly: Boolean
  ): Future[GetExpenses.Response] = {
    for {
      expenses <- expensesRepository.all(userId, from, to, unlinkedOnly)
      merchants <- merchantsRepository.all(userId)
      splits <- expensesRepository.allSplits(userId)
      receipts <- receiptRepository.all(userId)
      rows <- bankStatementRepository.allRows(userId)
    } yield {
      val merchantById = merchants.map(m => m.id -> m.name).toMap
      val splitsByExpenseId = splits.groupBy(_.expenseId)
      val receiptIdsByExpenseId = receipts
        .flatMap(r => r.expenseId.map(eid => eid -> r.id))
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap
      val rowIdsByExpenseId = rows
        .flatMap(r => r.expenseId.map(eid => eid -> r.id))
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap
      val data = expenses.map { expense =>
        val expSplits = splitsByExpenseId.getOrElse(expense.id, Nil).map { s =>
          GetExpenses.Response.Split(id = s.id, categoryId = s.categoryId, amount = s.amount)
        }
        GetExpenses.Response.Expense(
          id = expense.id,
          amount = expense.amount,
          date = expense.date,
          time = expense.time,
          merchantId = expense.merchantId,
          merchantName = expense.merchantId.flatMap(merchantById.get),
          notes = expense.notes,
          splits = expSplits,
          createdAt = expense.createdAt,
          receiptIds = receiptIdsByExpenseId.getOrElse(expense.id, Nil),
          rowIds = rowIdsByExpenseId.getOrElse(expense.id, Nil)
        )
      }
      GetExpenses.Response(data = data)
    }
  }
}
