package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.{
  CreateExpenseFromRow,
  CreateRefundFromRow,
  GetBankStatement,
  LinkRowToExpense
}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.utils.ReconciliationMatcher
import slinky.core.facade.SetStateHookCallback

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Sequential bulk runs over selected statement rows. Not a component: callers pass in the state they read and set. */
object BankStatementBulkRuns {
  import BankStatementReviewLogic.*

  def handleBulkIgnore(
      client: ApiClient,
      stmtId: UUID,
      ids: List[UUID],
      updateRows: (List[GetBankStatement.StatementRowData] => List[GetBankStatement.StatementRowData]) => Unit,
      setBulkIgnore: SetStateHookCallback[BulkRun],
      setSelectedRowIds: SetStateHookCallback[Set[UUID]]
  )(implicit ec: ExecutionContext): Unit = {
    val total = ids.length
    setBulkIgnore(BulkRun.Running(total, Nil))
    def loop(remaining: List[UUID]): Unit =
      remaining match {
        case Nil => setBulkIgnore(_.finished); setSelectedRowIds(Set.empty)
        case rowId :: rest =>
          client.ignoreRow(bankStatementId = stmtId, rowId = rowId).onComplete {
            case Success(_) =>
              updateRows(patchRow(_, rowId)(_.copy(ignoredAt = Some(Instant.now()))))
              setBulkIgnore(_.recorded(rowId, Right(())))
              loop(rest)
            case Failure(ex) =>
              setBulkIgnore(_.recorded(rowId, Left(ex.getMessage)))
              loop(rest)
          }
      }
    loop(ids)
  }

  // Matches are taken from this render; expenses linked during a bulk run are tracked in the loop instead.
  def handleBulkImport(
      client: ApiClient,
      stmtId: UUID,
      ids: List[UUID],
      statementRows: List[GetBankStatement.StatementRowData],
      rowInfoById: Map[UUID, RowInfo],
      expenseMatchById: Map[UUID, ReconciliationMatcher.ProposedAction],
      receiptMatchById: Map[UUID, UUID],
      expenseList: List[GetExpenses.Response.Expense],
      updateRows: (List[GetBankStatement.StatementRowData] => List[GetBankStatement.StatementRowData]) => Unit,
      setBulkImport: SetStateHookCallback[BulkRun],
      setSelectedRowIds: SetStateHookCallback[Set[UUID]],
      showToast: String => Unit,
      onReceiptLinked: UUID => Unit,
      onFinished: () => Unit
  )(implicit ec: ExecutionContext): Unit = {
    val total = ids.length
    setBulkImport(BulkRun.Running(total, Nil))
    final case class Counts(linked: Int, created: Int, ignored: Int, failed: Int)
    def loop(remaining: List[UUID], counts: Counts, linkedThisRun: Set[UUID]): Unit =
      remaining match {
        case Nil =>
          setBulkImport(_.finished)
          setSelectedRowIds(Set.empty)
          showToast(
            bulkImportSummary(
              linked = counts.linked,
              created = counts.created,
              ignored = counts.ignored,
              failed = counts.failed
            )
          )
          onFinished()
        case rowId :: rest =>
          def advance(outcome: Either[String, Unit], next: Counts, nextLinked: Set[UUID]): Unit = {
            setBulkImport(_.recorded(rowId, outcome))
            loop(rest, next, nextLinked)
          }
          def succeed(next: Counts): Unit = advance(Right(()), next, linkedThisRun)
          def fail(message: String): Unit =
            advance(Left(message), counts.copy(failed = counts.failed + 1), linkedThisRun)
          statementRows.find(_.id == rowId) match {
            case None => fail("Row not found")
            case Some(row) =>
              bulkRowDecision(
                rowInfoById.get(rowId),
                expenseMatchById.get(rowId),
                receiptMatchById.get(rowId),
                linkedThisRun
              ) match {
                case BulkRowDecision.Fail(message) => fail(message)
                case BulkRowDecision.Ignore =>
                  client.ignoreRow(bankStatementId = stmtId, rowId = rowId).onComplete {
                    case Success(_) =>
                      updateRows(patchRow(_, rowId)(_.copy(ignoredAt = Some(Instant.now()))))
                      succeed(counts.copy(ignored = counts.ignored + 1))
                    case Failure(ex) => fail(ex.getMessage)
                  }
                case BulkRowDecision.Link(expenseId) =>
                  client
                    .linkRowToExpense(bankStatementId = stmtId, rowId = rowId, LinkRowToExpense.Request(expenseId))
                    .onComplete {
                      case Success(_) =>
                        val expense = expenseList.find(_.id == expenseId)
                        // The server only reports a category for single-split expenses.
                        val categoryId = expense.flatMap(_.splits match {
                          case List(single) => Some(single.categoryId)
                          case _ => None
                        })
                        updateRows(
                          patchRow(_, rowId)(
                            _.copy(
                              expenseId = Some(expenseId),
                              expenseMerchantId = expense.flatMap(_.merchantId),
                              expenseCategoryId = categoryId
                            )
                          )
                        )
                        advance(Right(()), counts.copy(linked = counts.linked + 1), linkedThisRun + expenseId)
                      case Failure(ex) => fail(ex.getMessage)
                    }
                case BulkRowDecision.Create(res, receiptId) =>
                  val req = CreateExpenseFromRow.Request(
                    amount = row.amount.abs,
                    date = row.date,
                    time = None,
                    merchantId = res.merchantId,
                    notes = res.notes,
                    splits = List(CreateExpenseFromRow.Split(res.categoryId, row.amount.abs)),
                    receiptId = receiptId
                  )
                  client.createExpenseFromRow(bankStatementId = stmtId, rowId = rowId, req).onComplete {
                    case Success(resp) =>
                      updateRows(
                        patchRow(_, rowId)(
                          _.copy(
                            expenseId = Some(resp.expenseId),
                            expenseMerchantId = res.merchantId,
                            expenseCategoryId = Some(res.categoryId)
                          )
                        )
                      )
                      receiptId.foreach(onReceiptLinked)
                      succeed(counts.copy(created = counts.created + 1))
                    case Failure(ex) => fail(ex.getMessage)
                  }
                case BulkRowDecision.CreateRefund(prefill) =>
                  val req = CreateRefundFromRow.Request(
                    amount = row.amount.abs,
                    date = row.date,
                    merchantId = prefill.merchantId,
                    categoryId = prefill.categoryId,
                    expenseId = None,
                    notes = prefill.notes
                  )
                  client.createRefundFromRow(bankStatementId = stmtId, rowId = rowId, req).onComplete {
                    case Success(resp) =>
                      updateRows(patchRow(_, rowId)(_.copy(refundId = Some(resp.refundId))))
                      succeed(counts.copy(created = counts.created + 1))
                    case Failure(ex) => fail(ex.getMessage)
                  }
              }
          }
      }
    loop(ids, Counts(linked = 0, created = 0, ignored = 0, failed = 0), Set.empty)
  }
}
