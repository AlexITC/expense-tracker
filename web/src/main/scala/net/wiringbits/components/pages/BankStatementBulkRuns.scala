package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement, LinkRowToExpense}
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
      setStatementRows: SetStateHookCallback[List[GetBankStatement.StatementRowData]],
      setBulkIgnoreProgress: SetStateHookCallback[BulkProgress],
      setBulkIgnoreResults: SetStateHookCallback[BulkResults],
      setSelectedRowIds: SetStateHookCallback[Set[UUID]]
  )(implicit ec: ExecutionContext): Unit = {
    val total = ids.length
    setBulkIgnoreProgress(Some((0, total, 0)))
    setBulkIgnoreResults(Nil)
    def loop(remaining: List[UUID], done: Int, failed: Int): Unit =
      remaining match {
        case Nil => setBulkIgnoreProgress(None); setSelectedRowIds(Set.empty)
        case rowId :: rest =>
          client.ignoreRow(stmtId, rowId).onComplete {
            case Success(_) =>
              setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r))
              setBulkIgnoreResults(cur => cur :+ (rowId -> Right(())))
              setBulkIgnoreProgress(Some((done + 1, total, failed)))
              loop(rest, done + 1, failed)
            case Failure(ex) =>
              setBulkIgnoreResults(cur => cur :+ (rowId -> Left(ex.getMessage)))
              setBulkIgnoreProgress(Some((done + 1, total, failed + 1)))
              loop(rest, done + 1, failed + 1)
          }
      }
    loop(ids, 0, 0)
  }

  // Matches are taken from this render; expenses linked during a bulk run are tracked in the loop instead.
  def handleBulkImport(
      client: ApiClient,
      stmtId: UUID,
      ids: List[UUID],
      statementRows: List[GetBankStatement.StatementRowData],
      rowInfoById: Map[UUID, RowInfo],
      expenseMatchById: Map[UUID, ReconciliationMatcher.ProposedAction],
      expenseList: List[GetExpenses.Response.Expense],
      setStatementRows: SetStateHookCallback[List[GetBankStatement.StatementRowData]],
      setBulkImportProgress: SetStateHookCallback[BulkProgress],
      setBulkImportResults: SetStateHookCallback[BulkResults],
      setSelectedRowIds: SetStateHookCallback[Set[UUID]],
      showToast: String => Unit,
      onRefresh: () => Unit
  )(implicit ec: ExecutionContext): Unit = {
    val total = ids.length
    setBulkImportProgress(Some((0, total, 0)))
    setBulkImportResults(Nil)
    final case class Counts(linked: Int, created: Int, ignored: Int, failed: Int) {
      def done: Int = linked + created + ignored + failed
    }
    def loop(remaining: List[UUID], counts: Counts, linkedThisRun: Set[UUID]): Unit =
      remaining match {
        case Nil =>
          setBulkImportProgress(None)
          setSelectedRowIds(Set.empty)
          showToast(bulkImportSummary(counts.linked, counts.created, counts.ignored, counts.failed))
          onRefresh()
        case rowId :: rest =>
          def advance(outcome: Either[String, Unit], next: Counts, nextLinked: Set[UUID]): Unit = {
            setBulkImportResults(cur => cur :+ (rowId -> outcome))
            setBulkImportProgress(Some((next.done, total, next.failed)))
            loop(rest, next, nextLinked)
          }
          def succeed(next: Counts): Unit = advance(Right(()), next, linkedThisRun)
          def fail(message: String): Unit =
            advance(Left(message), counts.copy(failed = counts.failed + 1), linkedThisRun)
          statementRows.find(_.id == rowId) match {
            case None => fail("Row not found")
            case Some(row) =>
              bulkRowDecision(rowInfoById.get(rowId), expenseMatchById.get(rowId), linkedThisRun) match {
                case BulkRowDecision.Fail(message) => fail(message)
                case BulkRowDecision.Ignore =>
                  client.ignoreRow(stmtId, rowId).onComplete {
                    case Success(_) =>
                      setStatementRows(cur =>
                        cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r)
                      )
                      succeed(counts.copy(ignored = counts.ignored + 1))
                    case Failure(ex) => fail(ex.getMessage)
                  }
                case BulkRowDecision.Link(expenseId) =>
                  client
                    .linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId))
                    .onComplete {
                      case Success(_) =>
                        val expense = expenseList.find(_.id == expenseId)
                        // The server only reports a category for single-split expenses.
                        val categoryId = expense.flatMap(_.splits match {
                          case List(single) => Some(single.categoryId)
                          case _ => None
                        })
                        setStatementRows(cur =>
                          cur.map(r =>
                            if (r.id == rowId)
                              r.copy(
                                expenseId = Some(expenseId),
                                expenseMerchantId = expense.flatMap(_.merchantId),
                                expenseCategoryId = categoryId
                              )
                            else r
                          )
                        )
                        advance(Right(()), counts.copy(linked = counts.linked + 1), linkedThisRun + expenseId)
                      case Failure(ex) => fail(ex.getMessage)
                    }
                case BulkRowDecision.Create(res) =>
                  val req = CreateExpenseFromRow.Request(
                    amount = row.amount.abs,
                    date = row.date,
                    time = None,
                    merchantId = res.merchantId,
                    notes = Some(row.description).filter(_.nonEmpty),
                    splits = List(CreateExpenseFromRow.Split(res.categoryId, row.amount.abs)),
                    receiptId = None
                  )
                  client.createExpenseFromRow(stmtId, rowId, req).onComplete {
                    case Success(resp) =>
                      setStatementRows(cur =>
                        cur.map(r =>
                          if (r.id == rowId)
                            r.copy(
                              expenseId = Some(resp.expenseId),
                              expenseMerchantId = res.merchantId,
                              expenseCategoryId = Some(res.categoryId)
                            )
                          else r
                        )
                      )
                      succeed(counts.copy(created = counts.created + 1))
                    case Failure(ex) => fail(ex.getMessage)
                  }
              }
          }
      }
    loop(ids, Counts(0, 0, 0, 0), Set.empty)
  }
}
