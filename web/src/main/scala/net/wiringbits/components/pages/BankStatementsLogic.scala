package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.GetBankStatements

/** Pure logic behind the bank statements page: no React or MUI. */
object BankStatementsLogic {

  /** Account key of the most recently uploaded statement; on a tie the first in list order wins. */
  def defaultAccountKey(stmts: List[GetBankStatements.Response.BankStatement]): Option[String] =
    stmts.maxByOption(_.createdAt).map(BankStatementList.accountKeyOf)
}
