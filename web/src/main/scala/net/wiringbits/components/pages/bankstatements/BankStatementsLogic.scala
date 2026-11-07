package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.{GetBankStatement, GetBankStatements}

/** Pure logic behind the bank statements page: no React or MUI. */
object BankStatementsLogic {

  /** Account key of the most recently uploaded statement; on a tie the first in list order wins. */
  def defaultAccountKey(stmts: List[GetBankStatements.Response.BankStatement]): Option[String] =
    stmts.maxByOption(_.createdAt).map(BankStatementList.accountKeyOf)

  /** The chosen account while it still has statements; otherwise the default account. */
  def selectedAccountKey(chosen: String, stmts: List[GetBankStatements.Response.BankStatement]): Option[String] =
    Option(chosen)
      .filter(k => stmts.exists(s => BankStatementList.accountKeyOf(s) == k))
      .orElse(defaultAccountKey(stmts))

  /** The account's statements ordered by period (newest first), restricted to those whose filename matches
    * `nameFilter`, each paired with its older neighbour in the FULL timeline when that neighbour is also visible — so
    * the name filter can't make a hidden statement look like a missing cycle.
    */
  def timeline(
      stmts: List[GetBankStatements.Response.BankStatement],
      accountKey: Option[String],
      nameFilter: String
  ): List[(GetBankStatements.Response.BankStatement, Option[GetBankStatements.Response.BankStatement])] = {
    val accountStmts =
      stmts.filter(s => accountKey.contains(BankStatementList.accountKeyOf(s))).sortBy(_.periodStart.toEpochDay).reverse
    val needle = nameFilter.trim.toLowerCase
    def matches(s: GetBankStatements.Response.BankStatement): Boolean =
      needle.isEmpty || s.documentFilename.toLowerCase.contains(needle)
    val olderOf = accountStmts.zip(accountStmts.drop(1)).toMap
    accountStmts.filter(matches).map(s => s -> olderOf.get(s).filter(matches))
  }

  private def plural(n: Int, singular: String, pluralForm: String): String =
    s"$n ${if (n == 1) singular else pluralForm}"

  /** Lines describing what deleting a statement does, given its rows. Only rows linked to an expense count as linked
    * expenses (refund-only or ignored rows don't). Deleting keeps those expenses but unlinks them.
    */
  def deleteSummaryLines(
      s: GetBankStatements.Response.BankStatement,
      rows: List[GetBankStatement.StatementRowData]
  ): List[String] = {
    val linked = rows.count(_.expenseId.isDefined)
    List(
      s"File: ${s.documentFilename}",
      s"Account: ${BankStatementList.accountLabelOf(s)}",
      s"Period: ${s.periodStart} → ${s.periodEnd}",
      s"Rows: ${rows.size}",
      s"${plural(linked, "linked expense", "linked expenses")} will be kept but unlinked from this statement.",
      "This action cannot be undone."
    )
  }
}
