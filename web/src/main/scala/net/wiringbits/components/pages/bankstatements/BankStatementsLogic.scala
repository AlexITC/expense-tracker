package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.GetBankStatements

import scala.scalajs.js.URIUtils
import scala.util.Try

/** Pure logic behind the bank statements page: no React or MUI. */
object BankStatementsLogic {

  /** The list page's URL filters; "" means the param is absent. */
  case class ListQuery(account: String, name: String)

  /** Reads `account` and `q` from a `location.search` string (with or without the leading `?`); the first occurrence
    * wins. Built on `URIUtils` rather than `URLSearchParams` because the jsdom used by the test env lacks the latter.
    */
  def parseListQuery(search: String): ListQuery = {
    // Form-style decoding: `+` is a space; a malformed escape (e.g. a hand-typed `50%`) is kept as-is.
    def decode(raw: String): String = {
      val spaced = raw.replace('+', ' ')
      Try(URIUtils.decodeURIComponent(spaced)).getOrElse(spaced)
    }
    val params = search
      .stripPrefix("?")
      .split('&')
      .toList
      .filter(_.nonEmpty)
      .map { pair =>
        val (k, v) = pair.span(_ != '=')
        decode(k) -> decode(v.drop(1))
      }
    def get(key: String): String = params.collectFirst { case (`key`, v) => v }.getOrElse("")
    ListQuery(account = get("account"), name = get("q"))
  }

  /** `/bank-statements` with only the non-empty filters as (encoded) query params. */
  def listPath(account: Option[String], name: String): String = {
    val params = List("account" -> account.getOrElse(""), "q" -> name).filter(_._2.nonEmpty)
    if (params.isEmpty) "/bank-statements"
    else params.map { case (k, v) => s"$k=${URIUtils.encodeURIComponent(v)}" }.mkString("/bank-statements?", "&", "")
  }

  /** An "account" groups a user's statements by bank + product type + last 4 digits. */
  def accountKey(bankName: String, productType: String, accountLast4: String): String =
    s"$bankName~$productType~$accountLast4"

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
