package net.wiringbits.actions.receipts

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.actions.expenses.ExpenseValidator
import net.wiringbits.api.models.receipts.ConfirmReceipt
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.repositories.{ExpensesRepository, ImportRuleRepository, ReceiptRepository}
import net.wiringbits.repositories.models.{Expense, ExpenseCategorySplit}

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ConfirmReceiptAction @Inject() (
    receiptRepository: ReceiptRepository,
    expensesRepository: ExpensesRepository,
    ruleRepository: ImportRuleRepository,
    validator: ExpenseValidator
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(receiptId: UUID, userId: UUID, request: ConfirmReceipt.Request): Future[ConfirmReceipt.Response] =
    for {
      receiptOpt <- receiptRepository.find(receiptId, userId)
      receipt = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      _ <-
        if (receipt.status != DocumentStatus.Parsed)
          Future.failed(new RuntimeException("Receipt is not in Parsed status"))
        else Future.unit
      _ <-
        if (receipt.expenseId.isDefined)
          Future.failed(new RuntimeException("Receipt is already linked to an expense — unlink first"))
        else Future.unit
      amount = request.amount.getOrElse(throw new RuntimeException("Receipt confirm requires 'amount'"))
      date = request.date.getOrElse(throw new RuntimeException("Receipt confirm requires 'date'"))
      _ <- validator.validate(
        userId,
        amount,
        request.notes,
        request.merchantId,
        request.splits.map(s => (s.categoryId, s.amount))
      )
      _ <- expensesRepository
        .countByMonth(userId, date.getYear, date.getMonthValue)
        .flatMap { count =>
          if (count >= 500)
            Future.failed(new RuntimeException("Maximum number of expenses for this month (500) reached"))
          else Future.unit
        }
      now = clock.instant()
      expenseId = UuidCreator.getTimeOrderedEpoch()
      expense = Expense.Create(
        id = expenseId,
        userId = userId,
        amount = amount,
        date = date,
        time = request.time,
        merchantId = request.merchantId,
        notes = request.notes,
        createdAt = now,
        updatedAt = now
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
      createdExpenseId <- receiptRepository.confirm(receiptId, userId, expense, splits, request.rowId)
      _ <- (request.parsedMerchantName, request.merchantId) match {
        case (Some(parsedName), Some(mId)) =>
          ruleRepository.createSetMerchantIfAbsent(userId, mId, parsedName)
        case _ => Future.unit
      }
    } yield ConfirmReceipt.Response(Some(createdExpenseId))
}
