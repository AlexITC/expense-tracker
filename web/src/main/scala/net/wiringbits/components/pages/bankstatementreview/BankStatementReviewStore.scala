package net.wiringbits.components.pages.bankstatementreview

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.{AiSuggestions, GetBankStatement}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic.{Loadable, ReviewSupport}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** The review page's state for one statement, split so each part notifies only its own subscribers. `$statement` holds
  * the metadata and is never changed once loaded; rows and suggestions change independently of it.
  */
final class BankStatementReviewStore(using Scheduler) {
  val $statement: Var[Loadable[GetBankStatement.Response]] = Var(Loadable.Loading)
  val $rows: Var[List[GetBankStatement.StatementRowData]] = Var(Nil)
  val $support: Var[Loadable[ReviewSupport]] = Var(Loadable.Loading)
  val $suggestions: Var[Option[AiSuggestions]] = Var(None)

  /** Loads the statement first, then the support lists for it, recording each stage's outcome. */
  def load(client: ApiClient, stmtId: UUID)(using ExecutionContext): Unit =
    client.getBankStatement(stmtId).onComplete {
      case Success(stmt) =>
        statementLoaded(stmt)
        BankStatementReviewStore.loadSupport(client, stmt).onComplete {
          case Success(support) => supportLoaded(support)
          case Failure(ex) => supportFailed(ex.getMessage)
        }
      case Failure(ex) => statementFailed(ex.getMessage)
    }

  def statementLoaded(resp: GetBankStatement.Response): Unit = {
    $rows := resp.statementRows
    $suggestions := resp.aiSuggestions
    $statement := Loadable.Loaded(resp)
  }

  def statementFailed(message: String): Unit = $statement := Loadable.Failed(message)

  def supportLoaded(support: ReviewSupport): Unit = $support := Loadable.Loaded(support)

  def supportFailed(message: String): Unit = $support := Loadable.Failed(message)

  def updateRows(f: List[GetBankStatement.StatementRowData] => List[GetBankStatement.StatementRowData]): Unit =
    $rows := f($rows())

  def patchRow(rowId: UUID)(f: GetBankStatement.StatementRowData => GetBankStatement.StatementRowData): Unit =
    updateRows(BankStatementReviewLogic.patchRow(_, rowId)(f))

  /** Ignored until the support lists are loaded, so an early update cannot fabricate them. */
  def updateSupport(f: ReviewSupport => ReviewSupport): Unit = $support() match {
    case Loadable.Loaded(s) => $support := Loadable.Loaded(f(s))
    case _ => ()
  }

  def setSuggestions(s: Option[AiSuggestions]): Unit = $suggestions := s
}

object BankStatementReviewStore {

  /** Fetches the merchants, categories, rules, the statement's candidate expenses and the unlinked receipts that have
    * an amount, in parallel.
    */
  def loadSupport(client: ApiClient, stmt: GetBankStatement.Response)(using ExecutionContext): Future[ReviewSupport] = {
    val (from, to) = BankStatementReviewLogic.candidateExpenseWindow(stmt)
    val merchantsF = client.getMerchants
    val categoriesF = client.getCategories
    val rulesF = client.getImportRules
    val expensesF = client.getExpensesFiltered(Some(from), Some(to), unlinkedOnly = true)
    val receiptsF = client.getReceipts
    for {
      merchants <- merchantsF
      categories <- categoriesF
      rules <- rulesF
      expenses <- expensesF
      receipts <- receiptsF
    } yield ReviewSupport(
      merchants = merchants.data,
      categories = categories.data,
      rules = rules.data.map(ImportRuleMatcher.Rule.fromApi),
      expenses = expenses.data,
      receipts = receipts.data.filter(r => r.expenseId.isEmpty && r.amount.isDefined)
    )
  }
}
