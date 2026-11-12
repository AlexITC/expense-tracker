package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.{GetAccountAliases, GetBankStatement, GetBankStatements}

import java.time.LocalDate
import java.util.UUID
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

  /** An "account" groups a user's statements by bank + product type + last 4 + currency — the same tuple the nickname
    * is keyed by, so each account maps to exactly one nickname. The key also appears in `?account=` URLs.
    */
  def accountKey(bankName: String, productType: String, accountLast4: String, currency: String): String =
    s"$bankName~$productType~$accountLast4~$currency"

  /** Display label of an account: the nickname, when set, in front of the bank/product/last-4/currency label. An empty
    * nickname counts as none.
    */
  /** The account's label. The nickname, when set, replaces the identity so the account picker and list read as the
    * nickname alone; pass `alias = None` where the nickname is shown separately (e.g. next to [[AccountAliasEditor]]).
    */
  def accountLabel(
      bankName: String,
      productType: String,
      accountLast4: String,
      currency: String,
      alias: Option[String]
  ): String = {
    val default = s"$bankName (${BankStatementList.productLabel(productType)}) ••••$accountLast4 · $currency"
    alias.filter(_.nonEmpty).getOrElse(default)
  }

  /** The user's nicknames keyed by account, for looking one up from a statement's account key. */
  def aliasByKey(aliases: GetAccountAliases.Response): Map[String, String] =
    aliases.accounts.map { a =>
      accountKey(
        bankName = a.bankName,
        productType = a.productType,
        accountLast4 = a.accountLast4,
        currency = a.currency
      ) -> a.alias
    }.toMap

  /** The newest-created statement of the account; the same one whose label the account dropdown shows. */
  def accountRepresentative(
      stmts: List[GetBankStatements.Response.BankStatement],
      accountKey: Option[String]
  ): Option[GetBankStatements.Response.BankStatement] =
    stmts.filter(s => accountKey.contains(BankStatementList.accountKeyOf(s))).maxByOption(_.createdAt)

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

  /** Replaces the polled statement in the list. A single statement response carries no overlap info, so the previously
    * known overlaps are kept.
    */
  def mergePolled(
      stmts: List[GetBankStatements.Response.BankStatement],
      updated: GetBankStatement.Response
  ): List[GetBankStatements.Response.BankStatement] =
    stmts.map { s =>
      if (s.id != updated.id) s
      else
        GetBankStatements.Response.BankStatement(
          id = updated.id,
          documentFilename = updated.documentFilename,
          documentStatus = updated.documentStatus,
          bankName = updated.bankName,
          productType = updated.productType,
          currency = updated.currency,
          accountLast4 = updated.accountLast4,
          periodStart = updated.periodStart,
          periodEnd = updated.periodEnd,
          createdAt = updated.createdAt,
          totalRows = updated.statementRows.size,
          processedRows = updated.statementRows.count(row =>
            row.expenseId.isDefined || row.refundId.isDefined || row.ignoredAt.isDefined
          ),
          overlappingStatementIds = s.overlappingStatementIds
        )
    }

  /** Drops a deleted statement and removes it from the others' overlaps, so their duplicate badges clear. */
  def removeStatement(
      stmts: List[GetBankStatements.Response.BankStatement],
      id: UUID
  ): List[GetBankStatements.Response.BankStatement] =
    stmts.filterNot(_.id == id).map(s => s.copy(overlappingStatementIds = s.overlappingStatementIds.filterNot(_ == id)))

  /** Warning text when `s` overlaps other statements of the same account; ids missing from `all` are counted. */
  def duplicateBadgeText(
      s: GetBankStatements.Response.BankStatement,
      all: List[GetBankStatements.Response.BankStatement]
  ): Option[String] =
    Option.when(s.overlappingStatementIds.nonEmpty) {
      val byId = all.map(x => x.id -> x.documentFilename).toMap
      val names = s.overlappingStatementIds.flatMap(byId.get)
      val missing = s.overlappingStatementIds.size - names.size
      val parts = names ++ Option.when(missing > 0)(
        plural(missing, singular = "other statement", pluralForm = "other statements")
      )
      s"⚠ Possible duplicate — overlaps ${parts.mkString(", ")}"
    }

  private def plural(n: Int, singular: String, pluralForm: String): String =
    s"$n ${if (n == 1) singular else pluralForm}"

  /** A gap this many days or less between an older statement's end and the next one's start is treated as consecutive;
    * a larger gap means at least one whole cycle is missing.
    */
  private val MaxConsecutiveGapDays = 20L

  /** The coverage range that is actually missing between two period-adjacent statements of one account: from the day
    * after the older statement's end to the day before the newer statement's start. `None` when they are close enough
    * to be consecutive. Generalizes to multi-cycle gaps.
    */
  def gapMissingRange(
      older: GetBankStatements.Response.BankStatement,
      newer: GetBankStatements.Response.BankStatement
  ): Option[(LocalDate, LocalDate)] =
    Option.when(newer.periodStart.toEpochDay - older.periodEnd.toEpochDay > MaxConsecutiveGapDays)(
      (older.periodEnd.plusDays(1), newer.periodStart.minusDays(1))
    )

  def gapWarningText(
      older: GetBankStatements.Response.BankStatement,
      newer: GetBankStatements.Response.BankStatement
  ): Option[String] =
    gapMissingRange(older = older, newer = newer).map { case (missingStart, missingEnd) =>
      s"⚠ Gap — a statement covering $missingStart to $missingEnd appears to be missing"
    }

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
      s"Account: ${accountLabel(bankName = s.bankName, productType = s.productType, accountLast4 = s.accountLast4, currency = s.currency, alias = None)}",
      s"Period: ${s.periodStart} → ${s.periodEnd}",
      s"Rows: ${rows.size}",
      s"${plural(linked, singular = "linked expense", pluralForm = "linked expenses")} will be kept but unlinked from this statement.",
      "This action cannot be undone."
    )
  }
}
