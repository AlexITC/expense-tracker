package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.GetBankStatements

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
}
