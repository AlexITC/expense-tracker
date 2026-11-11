package net.wiringbits.components.pages.bankstatementreview

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.{AiSuggestions, GetAccountAliases, GetBankStatement}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic.{Loadable, ReviewSupport}
import net.wiringbits.components.pages.bankstatements.BankStatementsLogic

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** The review page's state for one statement, split so each part notifies only its own subscribers. `$statement` holds
  * the metadata and never changes after loading; the account nickname lives in its own `$accountAlias`, and rows and
  * suggestions change independently.
  */
final class BankStatementReviewStore(using Scheduler) {
  val $statement: Var[Loadable[GetBankStatement.Response]] = Var(Loadable.Loading)
  val $rows: Var[List[GetBankStatement.StatementRowData]] = Var(Nil)
  val $support: Var[Loadable[ReviewSupport]] = Var(Loadable.Loading)
  val $suggestions: Var[Option[AiSuggestions]] = Var(None)
  val $accountAlias: Var[String] = Var("")

  /** Loads the statement first, then the support lists for it, recording each stage's outcome. The account nickname is
    * fetched alongside; its failure is non-critical (no nickname shown) and never blocks the page.
    */
  def load(client: ApiClient, stmtId: UUID)(using ExecutionContext): Unit =
    client.getBankStatement(stmtId).onComplete {
      case Success(stmt) =>
        statementLoaded(stmt)
        client.getBankStatementAccountAliases.foreach(accountAliasLoaded(stmt, _))
        BankStatementReviewStore.loadSupport(client, stmt).onComplete {
          case Success(support) => supportLoaded(support)
          case Failure(ex) => supportFailed(ex.getMessage)
        }
      case Failure(ex) => statementFailed(ex.getMessage)
    }

  /** Picks this statement's account nickname out of the user's aliases, by the account tuple. */
  def accountAliasLoaded(stmt: GetBankStatement.Response, aliases: GetAccountAliases.Response): Unit = {
    val key = BankStatementsLogic.accountKey(
      bankName = stmt.bankName,
      productType = stmt.productType,
      accountLast4 = stmt.accountLast4,
      currency = stmt.currency
    )
    $accountAlias := BankStatementsLogic.aliasByKey(aliases).getOrElse(key, "")
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

  def setAccountAlias(alias: String): Unit = $accountAlias := alias

  /** Refetches the unlinked receipts, so one a form just attached can no longer match another row. A failure keeps the
    * current list and is reported through `onError`.
    */
  def refreshReceipts(client: ApiClient, onError: String => Unit)(using ExecutionContext): Unit =
    client.getReceipts.onComplete {
      case Success(resp) =>
        val receipts = BankStatementReviewStore.matchableReceipts(resp.data)
        updateSupport(_.copy(receipts = receipts))
      case Failure(ex) => onError(ex.getMessage)
    }
}

object BankStatementReviewStore {

  /** Fetches the merchants, categories, rules, the statement's candidate expenses and the unlinked receipts that have
    * an amount, in parallel. Receipts only feed the optional auto-match, so a failed receipts fetch yields no receipts
    * instead of failing the support lists.
    */
  def loadSupport(client: ApiClient, stmt: GetBankStatement.Response)(using ExecutionContext): Future[ReviewSupport] = {
    val (from, to) = BankStatementReviewLogic.candidateExpenseWindow(stmt)
    val merchantsF = client.getMerchants
    val categoriesF = client.getCategories
    val rulesF = client.getImportRules
    val expensesF = client.getExpensesFiltered(from = Some(from), to = Some(to), unlinkedOnly = true)
    val receiptsF = client.getReceipts.map(r => matchableReceipts(r.data)).recover { case _ => Nil }
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
      receipts = receipts
    )
  }

  /** The receipts a row can still match: not linked to an expense and with an amount. */
  def matchableReceipts(receipts: List[GetReceipts.Response.Receipt]): List[GetReceipts.Response.Receipt] =
    receipts.filter(r => r.expenseId.isEmpty && r.amount.isDefined)
}
