package net.wiringbits.components.pages

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.bankstatements.{AiSuggestions, GetBankStatement}
import net.wiringbits.components.pages.BankStatementReviewLogic.{Loadable, ReviewSupport}

import java.util.UUID

/** The review page's state for one statement, split so each part notifies only its own subscribers. `$statement` holds
  * the metadata and is never changed once loaded; rows and suggestions change independently of it.
  */
final class BankStatementReviewStore(using Scheduler) {
  val $statement: Var[Loadable[GetBankStatement.Response]] = Var(Loadable.Loading)
  val $rows: Var[List[GetBankStatement.StatementRowData]] = Var(Nil)
  val $support: Var[Loadable[ReviewSupport]] = Var(Loadable.Loading)
  val $suggestions: Var[Option[AiSuggestions]] = Var(None)

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
