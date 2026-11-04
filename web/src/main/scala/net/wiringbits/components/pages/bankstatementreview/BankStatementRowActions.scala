package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.expenses.GetExpenses
import slinky.core.facade.SetStateHookCallback

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Single-row actions of the review page. Not a component: built from the client and the state setters it updates. */
final case class BankStatementRowActions(
    client: ApiClient,
    stmtId: UUID,
    expenseWindow: (LocalDate, LocalDate),
    updateRows: (List[GetBankStatement.StatementRowData] => List[GetBankStatement.StatementRowData]) => Unit,
    setSelectedRowIds: SetStateHookCallback[Set[UUID]],
    setExpenses: List[GetExpenses.Response.Expense] => Unit,
    setError: SetStateHookCallback[String],
    showToast: String => Unit
)(implicit ec: ExecutionContext) {
  import BankStatementReviewLogic.{patchRow, replaceRow}

  def ignore(rowId: UUID): Unit =
    client.ignoreRow(stmtId, rowId).onComplete {
      case Success(_) =>
        setError("")
        setSelectedRowIds(cur => cur - rowId)
        updateRows(patchRow(_, rowId)(_.copy(ignoredAt = Some(Instant.now()))))
        showToast("Row ignored")
      case Failure(ex) => setError(ex.getMessage)
    }

  def unignore(rowId: UUID): Unit =
    client.unignoreRow(stmtId, rowId).onComplete {
      case Success(_) =>
        setError("")
        updateRows(patchRow(_, rowId)(_.copy(ignoredAt = None)))
      case Failure(ex) => setError(ex.getMessage)
    }

  def unlink(rowId: UUID): Unit =
    client.unlinkRow(stmtId, rowId).onComplete {
      case Success(_) =>
        setError("")
        updateRows(patchRow(_, rowId)(_.copy(expenseId = None, expenseMerchantId = None, expenseCategoryId = None)))
        // The expense is unlinked again, so keep the expense list current.
        refreshExpenses()
      case Failure(ex) => setError(ex.getMessage)
    }

  def unlinkRefund(rowId: UUID): Unit =
    client.unlinkRowRefund(stmtId, rowId).onComplete {
      case Success(_) =>
        setError("")
        updateRows(patchRow(_, rowId)(_.copy(refundId = None)))
      case Failure(ex) => setError(ex.getMessage)
    }

  // The drawer forms only report the new expense id, so re-read the row to get the expense's merchant and category.
  // Single-row changes refresh just that row and the expense list, so the page is not remounted.
  def refreshRow(rowId: UUID): Unit = {
    client.getBankStatement(stmtId).onComplete {
      case Success(full) =>
        full.statementRows.find(_.id == rowId).foreach(fresh => updateRows(replaceRow(_, fresh)))
      case Failure(ex) => setError(ex.getMessage)
    }
    refreshExpenses()
  }

  def refreshExpenses(): Unit = {
    val (from, to) = expenseWindow
    client.getExpensesFiltered(Some(from), Some(to), unlinkedOnly = true).onComplete {
      case Success(resp) => setExpenses(resp.data)
      case Failure(ex) => setError(ex.getMessage)
    }
  }
}
