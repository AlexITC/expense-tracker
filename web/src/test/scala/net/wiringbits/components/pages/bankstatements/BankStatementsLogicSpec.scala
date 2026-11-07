package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.{GetBankStatement, GetBankStatements}
import net.wiringbits.api.models.documents.DocumentStatus
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementsLogicSpec extends AnyWordSpec {

  private def stmt(
      accountLast4: String,
      createdAt: Instant,
      bankName: String = "BBVA",
      productType: String = "credit",
      periodStart: LocalDate = LocalDate.of(2026, 4, 1),
      documentFilename: String = "s.pdf",
      totalRows: Int = 0,
      processedRows: Int = 0,
      overlappingStatementIds: List[UUID] = Nil
  ): GetBankStatements.Response.BankStatement =
    GetBankStatements.Response.BankStatement(
      id = UUID.randomUUID(),
      documentFilename = documentFilename,
      documentStatus = DocumentStatus.Parsed,
      bankName = bankName,
      productType = productType,
      currency = "EUR",
      accountLast4 = accountLast4,
      periodStart = periodStart,
      periodEnd = periodStart.plusMonths(1).minusDays(1),
      createdAt = createdAt,
      totalRows = totalRows,
      processedRows = processedRows,
      overlappingStatementIds = overlappingStatementIds
    )

  private val t0 = Instant.parse("2026-05-01T10:00:00Z")

  "defaultAccountKey" should {
    "pick the account of the newest statement even when it is not first" in {
      val stmts = List(
        stmt(accountLast4 = "1111", createdAt = t0),
        stmt(accountLast4 = "2222", createdAt = t0.plusSeconds(3600)),
        stmt(accountLast4 = "3333", createdAt = t0.minusSeconds(3600))
      )
      BankStatementsLogic.defaultAccountKey(stmts) mustBe Some("BBVA~credit~2222")
    }

    "return None for an empty list" in {
      BankStatementsLogic.defaultAccountKey(Nil) mustBe None
    }

    "pick the first statement in list order on a createdAt tie" in {
      val stmts = List(
        stmt(accountLast4 = "1111", createdAt = t0),
        stmt(accountLast4 = "2222", createdAt = t0)
      )
      BankStatementsLogic.defaultAccountKey(stmts) mustBe Some("BBVA~credit~1111")
    }
  }

  "selectedAccountKey" should {
    val stmts = List(
      stmt(accountLast4 = "1111", createdAt = t0),
      stmt(accountLast4 = "2222", createdAt = t0.plusSeconds(3600))
    )

    "keep the chosen account while it exists, even when another account is newer" in {
      BankStatementsLogic.selectedAccountKey("BBVA~credit~1111", stmts) mustBe Some("BBVA~credit~1111")
    }

    "fall back to the newest account when the chosen one is gone" in {
      BankStatementsLogic.selectedAccountKey("BBVA~credit~9999", stmts) mustBe Some("BBVA~credit~2222")
    }

    "return None when nothing is chosen and there are no statements" in {
      BankStatementsLogic.selectedAccountKey("", Nil) mustBe None
    }
  }

  "timeline" should {
    def month(m: Int): LocalDate = LocalDate.of(2026, m, 1)
    val key = Some("BBVA~credit~1111")
    val jan = stmt(accountLast4 = "1111", createdAt = t0, periodStart = month(1), documentFilename = "jan.pdf")
    val feb = stmt(accountLast4 = "1111", createdAt = t0, periodStart = month(2), documentFilename = "feb.pdf")
    val mar = stmt(accountLast4 = "1111", createdAt = t0, periodStart = month(3), documentFilename = "mar.pdf")
    val other = stmt(accountLast4 = "2222", createdAt = t0, periodStart = month(2), documentFilename = "other.pdf")

    "return only the selected account's statements, newest period first" in {
      BankStatementsLogic.timeline(List(feb, other, jan, mar), key, "").map(_._1) mustBe List(mar, feb, jan)
    }

    "return nothing when no account is selected" in {
      BankStatementsLogic.timeline(List(jan, other), None, "") mustBe Nil
    }

    "include fully processed statements" in {
      val done = stmt(accountLast4 = "1111", createdAt = t0, totalRows = 5, processedRows = 5)
      BankStatementsLogic.timeline(List(done), key, "").map(_._1) mustBe List(done)
    }

    "pair each statement with its older neighbour" in {
      BankStatementsLogic.timeline(List(jan, feb, mar), key, "") mustBe
        List(mar -> Some(feb), feb -> Some(jan), jan -> None)
    }

    "hide statements whose filename does not match the name filter, ignoring case and surrounding spaces" in {
      BankStatementsLogic.timeline(List(jan, feb, mar), key, " MAR ").map(_._1) mustBe List(mar)
    }

    "drop the older neighbour when the name filter hides it, instead of pairing with a farther statement" in {
      // "a" matches jan.pdf and mar.pdf but not feb.pdf
      BankStatementsLogic.timeline(List(jan, feb, mar), key, "a") mustBe List(mar -> None, jan -> None)
    }
  }

  "parseListQuery" should {
    "return empty values for an empty search" in {
      BankStatementsLogic.parseListQuery("") mustBe BankStatementsLogic.ListQuery(account = "", name = "")
    }

    "return empty values for a bare '?'" in {
      BankStatementsLogic.parseListQuery("?") mustBe BankStatementsLogic.ListQuery(account = "", name = "")
    }

    "read the account alone" in {
      BankStatementsLogic.parseListQuery("?account=BBVA~credit~1234") mustBe
        BankStatementsLogic.ListQuery(account = "BBVA~credit~1234", name = "")
    }

    "read both params" in {
      BankStatementsLogic.parseListQuery("?account=BBVA~credit~1234&q=april") mustBe
        BankStatementsLogic.ListQuery(account = "BBVA~credit~1234", name = "april")
    }

    "decode URL-encoded values" in {
      BankStatementsLogic.parseListQuery("?account=Banco+de+Chile%7Edebit%7E9876&q=mar%C3%A7o%20%26%20abril") mustBe
        BankStatementsLogic.ListQuery(account = "Banco de Chile~debit~9876", name = "março & abril")
    }

    "keep a malformed escape as typed instead of failing" in {
      BankStatementsLogic.parseListQuery("?q=50%") mustBe BankStatementsLogic.ListQuery(account = "", name = "50%")
    }

    "keep the first occurrence of a repeated param" in {
      BankStatementsLogic.parseListQuery("?account=A&account=B") mustBe
        BankStatementsLogic.ListQuery(account = "A", name = "")
    }

    "treat a valueless param as empty and skip empty segments" in {
      BankStatementsLogic.parseListQuery("?account&q=x&&") mustBe
        BankStatementsLogic.ListQuery(account = "", name = "x")
    }
  }

  "listPath" should {
    "omit the query string when there are no filters" in {
      BankStatementsLogic.listPath(account = None, name = "") mustBe "/bank-statements"
      BankStatementsLogic.listPath(account = Some(""), name = "") mustBe "/bank-statements"
    }

    "include only the non-empty params" in {
      BankStatementsLogic.listPath(account = None, name = "april") mustBe "/bank-statements?q=april"
    }

    "round-trip values with spaces and special characters through parseListQuery" in {
      val account = "Banco de Chile~debit~9876"
      val name = "estado 50% & más?#="
      val path = BankStatementsLogic.listPath(account = Some(account), name = name)
      path must startWith("/bank-statements?")
      BankStatementsLogic.parseListQuery(path.dropWhile(_ != '?')) mustBe
        BankStatementsLogic.ListQuery(account = account, name = name)
    }
  }

  "accountKey" should {
    "join bank, product type and last 4 with ~" in {
      BankStatementsLogic.accountKey(bankName = "Banco de Chile", productType = "debit", accountLast4 = "1234") mustBe
        "Banco de Chile~debit~1234"
    }
  }

  "deleteSummaryLines" should {
    def row(
        expenseId: Option[UUID] = None,
        refundId: Option[UUID] = None,
        ignoredAt: Option[Instant] = None
    ): GetBankStatement.StatementRowData =
      GetBankStatement.StatementRowData(
        id = UUID.randomUUID(),
        index = 0,
        date = LocalDate.of(2026, 4, 2),
        amount = BigDecimal(10),
        description = "x",
        manuallyEdited = false,
        confirmedAt = None,
        expenseId = expenseId,
        refundId = refundId,
        ignoredAt = ignoredAt,
        txnType = "debit",
        expenseMerchantId = None,
        expenseCategoryId = None
      )
    val s = stmt(accountLast4 = "1234", createdAt = t0, documentFilename = "apr.pdf")

    "describe a statement with no rows" in {
      BankStatementsLogic.deleteSummaryLines(s, Nil) mustBe List(
        "File: apr.pdf",
        "Account: BBVA (Credit) ••••1234",
        "Period: 2026-04-01 → 2026-04-30",
        "Rows: 0",
        "0 linked expenses will be kept but unlinked from this statement.",
        "This action cannot be undone."
      )
    }

    "count no linked expenses when no row is linked" in {
      val lines = BankStatementsLogic.deleteSummaryLines(s, List(row(), row()))
      lines must contain("Rows: 2")
      lines must contain("0 linked expenses will be kept but unlinked from this statement.")
    }

    "count only rows linked to an expense, not refund-only or ignored rows" in {
      val rows = List(
        row(expenseId = Some(UUID.randomUUID())),
        row(expenseId = Some(UUID.randomUUID())),
        row(refundId = Some(UUID.randomUUID())),
        row(ignoredAt = Some(t0)),
        row()
      )
      val lines = BankStatementsLogic.deleteSummaryLines(s, rows)
      lines must contain("Rows: 5")
      lines must contain("2 linked expenses will be kept but unlinked from this statement.")
    }

    "use the singular for exactly one linked expense" in {
      val lines = BankStatementsLogic.deleteSummaryLines(s, List(row(expenseId = Some(UUID.randomUUID())), row()))
      lines must contain("1 linked expense will be kept but unlinked from this statement.")
    }
  }

  "duplicateBadgeText" should {
    "return None when the statement overlaps nothing" in {
      val s = stmt(accountLast4 = "1111", createdAt = t0)
      BankStatementsLogic.duplicateBadgeText(s, List(s)) mustBe None
    }

    "name the overlapping statements' files" in {
      val a = stmt(accountLast4 = "1111", createdAt = t0, documentFilename = "a.pdf")
      val b = stmt(accountLast4 = "1111", createdAt = t0, documentFilename = "b.pdf")
      val s = stmt(accountLast4 = "1111", createdAt = t0, overlappingStatementIds = List(a.id, b.id))
      BankStatementsLogic.duplicateBadgeText(s, List(s, a, b)) mustBe
        Some("⚠ Possible duplicate — overlaps a.pdf, b.pdf")
    }

    "count overlapping statements missing from the list" in {
      val a = stmt(accountLast4 = "1111", createdAt = t0, documentFilename = "a.pdf")
      val s = stmt(
        accountLast4 = "1111",
        createdAt = t0,
        overlappingStatementIds = List(a.id, UUID.randomUUID(), UUID.randomUUID())
      )
      BankStatementsLogic.duplicateBadgeText(s, List(s, a)) mustBe
        Some("⚠ Possible duplicate — overlaps a.pdf, 2 other statements")
    }
  }

  "mergePolled" should {
    "replace the polled statement while keeping its known overlaps" in {
      val other = UUID.randomUUID()
      val s = stmt(accountLast4 = "1111", createdAt = t0, overlappingStatementIds = List(other))
      val untouched = stmt(accountLast4 = "2222", createdAt = t0)
      val updated = GetBankStatement.Response(
        id = s.id,
        documentId = UUID.randomUUID(),
        documentFilename = s.documentFilename,
        documentStatus = DocumentStatus.Parsed,
        bankName = s.bankName,
        productType = s.productType,
        currency = s.currency,
        accountLast4 = s.accountLast4,
        periodStart = s.periodStart,
        periodEnd = s.periodEnd,
        statementRows = Nil,
        createdAt = s.createdAt,
        extractionInfo = None,
        aiSuggestions = None
      )
      BankStatementsLogic.mergePolled(List(s, untouched), updated) mustBe List(s, untouched)
    }
  }

  "removeStatement" should {
    "drop the statement and remove it from the others' overlaps" in {
      val a = stmt(accountLast4 = "1111", createdAt = t0)
      val b = stmt(accountLast4 = "1111", createdAt = t0, overlappingStatementIds = List(a.id))
      val result = BankStatementsLogic.removeStatement(List(a, b), a.id)
      result mustBe List(b.copy(overlappingStatementIds = Nil))
      BankStatementsLogic.duplicateBadgeText(result.head, result) mustBe None
    }
  }
}
