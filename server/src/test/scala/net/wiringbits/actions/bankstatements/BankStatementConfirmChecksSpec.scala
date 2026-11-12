package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.ConfirmWarningKind
import net.wiringbits.repositories.models.BankStatement
import net.wiringbits.validations.BankStatementConfirmChecks
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementConfirmChecksSpec extends AnyWordSpec with Matchers {

  private val today = LocalDate.parse("2026-11-07")

  private def kinds(start: String, end: String): List[ConfirmWarningKind] =
    BankStatementConfirmChecks
      .periodWarnings(start = LocalDate.parse(start), end = LocalDate.parse(end), today = today)
      .map(_.kind)

  private def statement(start: String, end: String): BankStatement =
    BankStatement(
      id = UUID.randomUUID(),
      documentId = UUID.randomUUID(),
      userId = UUID.randomUUID(),
      bankName = "BBVA",
      productType = "debit",
      currency = "EUR",
      accountLast4 = "1234",
      periodStart = LocalDate.parse(start),
      periodEnd = LocalDate.parse(end),
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH
    )

  "periodWarnings" should {
    "accept typical monthly periods" in {
      kinds("2026-01-01", "2026-01-31") must be(Nil)
      kinds("2026-02-01", "2026-02-28") must be(Nil)
      kinds("2026-03-03", "2026-04-02") must be(Nil)
    }

    "accept the length boundaries of 26 and 35 days" in {
      kinds("2026-01-01", "2026-01-27") must be(Nil)
      kinds("2026-01-01", "2026-02-05") must be(Nil)
    }

    "warn about periods of 25 or 36 days" in {
      kinds("2026-01-01", "2026-01-26") must be(List(ConfirmWarningKind.PeriodLength))
      kinds("2026-01-01", "2026-02-06") must be(List(ConfirmWarningKind.PeriodLength))
    }

    "warn about a reversed period without also warning about its length" in {
      kinds("2026-01-31", "2026-01-01") must be(List(ConfirmWarningKind.PeriodOrder))
    }

    "warn about a period ending after today" in {
      kinds("2026-10-15", "2026-11-14") must be(List(ConfirmWarningKind.FuturePeriod))
    }

    "not warn about a period ending today" in {
      kinds("2026-10-08", "2026-11-07") must be(Nil)
    }

    "mention the dates in the message" in {
      val warnings = BankStatementConfirmChecks.periodWarnings(
        start = LocalDate.parse("2026-01-31"),
        end = LocalDate.parse("2026-01-01"),
        today = today
      )
      warnings.map(_.message).mkString must (include("2026-01-31") and include("2026-01-01"))
    }
  }

  "overlapWarning" should {
    "return None when nothing overlaps" in {
      BankStatementConfirmChecks.overlapWarning(Nil) must be(None)
    }

    "return a single warning listing every overlapping period" in {
      val result = BankStatementConfirmChecks.overlapWarning(
        List(statement("2026-01-01", "2026-01-31"), statement("2026-02-01", "2026-02-28"))
      )

      result.map(_.kind) must be(Some(ConfirmWarningKind.Overlap))
      result.map(_.message).getOrElse("") must (include("2026-01-01 → 2026-01-31") and include(
        "2026-02-01 → 2026-02-28"
      ))
    }
  }

  "cardMismatchWarnings" should {
    val metadata = BankStatement.Metadata(
      bankName = "Test Bank",
      productType = "credit",
      currency = "USD",
      accountLast4 = "1234",
      periodStart = LocalDate.parse("2026-02-01"),
      periodEnd = LocalDate.parse("2026-02-28")
    )

    def existing(productType: String, currency: String): BankStatement =
      statement("2026-01-01", "2026-01-31").copy(
        bankName = "Test Bank",
        productType = productType,
        currency = currency,
        accountLast4 = "1234"
      )

    "return nothing when there are no mismatching statements" in {
      BankStatementConfirmChecks.cardMismatchWarnings(metadata, Nil) must be(Nil)
    }

    "warn only about the product type when only the type differs" in {
      val result = BankStatementConfirmChecks.cardMismatchWarnings(metadata, List(existing("debit", "USD")))

      result.map(_.kind) must be(List(ConfirmWarningKind.ProductTypeMismatch))
      result.head.message must (include("debit") and include("credit") and include("1234"))
    }

    "warn only about the currency when only the currency differs" in {
      val result = BankStatementConfirmChecks.cardMismatchWarnings(metadata, List(existing("credit", "EUR")))

      result.map(_.kind) must be(List(ConfirmWarningKind.CurrencyMismatch))
      result.head.message must (include("EUR") and include("USD") and include("1234"))
    }

    "warn about both when type and currency differ" in {
      val result = BankStatementConfirmChecks.cardMismatchWarnings(metadata, List(existing("debit", "EUR")))

      result.map(_.kind) must be(List(ConfirmWarningKind.ProductTypeMismatch, ConfirmWarningKind.CurrencyMismatch))
    }

    "list each differing value once, in first-seen order" in {
      val result = BankStatementConfirmChecks.cardMismatchWarnings(
        metadata,
        List(existing("debit", "EUR"), existing("prepaid", "USD"), existing("debit", "EUR"), existing("credit", "MXN"))
      )

      result.map(_.message) match {
        case List(types, currencies) =>
          types must include("as debit, prepaid before")
          currencies must include("in EUR, MXN before")
        case other => fail(s"Unexpected warnings: $other")
      }
    }
  }

  "rowsOutsidePeriodWarning" should {
    def check(rows: List[String], start: String, end: String) =
      BankStatementConfirmChecks.rowsOutsidePeriodWarning(
        rowDates = rows.map(LocalDate.parse),
        periodStart = LocalDate.parse(start),
        periodEnd = LocalDate.parse(end)
      )

    "not fire when every row is inside the period, including the boundary days" in {
      check(List("2026-01-01", "2026-01-15", "2026-01-31"), "2026-01-01", "2026-01-31") must be(None)
    }

    "not fire when there are no rows" in {
      check(Nil, "2026-01-01", "2026-01-31") must be(None)
    }

    "fire for a row before the period start" in {
      check(List("2025-12-31", "2026-01-15"), "2026-01-01", "2026-01-31").map(_.kind) must be(
        Some(ConfirmWarningKind.RowsOutsidePeriod)
      )
    }

    "fire for a row after the period end" in {
      check(List("2026-01-15", "2026-02-01"), "2026-01-01", "2026-01-31").map(_.kind) must be(
        Some(ConfirmWarningKind.RowsOutsidePeriod)
      )
    }

    "fire when every row is outside the period" in {
      check(List("2026-03-01", "2026-03-02"), "2026-01-01", "2026-01-31").map(_.kind) must be(
        Some(ConfirmWarningKind.RowsOutsidePeriod)
      )
    }

    "fire for every row when the period is reversed" in {
      val result = check(List("2026-01-15"), "2026-01-31", "2026-01-01")

      result.map(_.kind) must be(Some(ConfirmWarningKind.RowsOutsidePeriod))
      result.map(_.message).getOrElse("") must include("1 of 1 rows")
    }

    "mention the count, the period and the earliest and latest out-of-range dates" in {
      val result = check(List("2026-02-03", "2025-12-30", "2026-01-15", "2026-02-01"), "2026-01-01", "2026-01-31")

      result.map(_.message).getOrElse("") must (include("3 of 4 rows") and include("2026-01-01 → 2026-01-31") and
        include("2025-12-30") and include("2026-02-03"))
    }
  }

  "rowsInOtherStatementWarning" should {
    val metadata = BankStatement.Metadata(
      bankName = "BBVA",
      productType = "debit",
      currency = "EUR",
      accountLast4 = "1234",
      periodStart = LocalDate.parse("2025-12-01"),
      periodEnd = LocalDate.parse("2025-12-31")
    )
    val november = statement("2025-11-01", "2025-11-30")

    def check(rows: List[String], sameCard: List[BankStatement]) =
      BankStatementConfirmChecks.rowsInOtherStatementWarning(
        metadata = metadata,
        rowDates = rows.map(LocalDate.parse),
        sameCard = sameCard
      )

    "fire when a row lands inside an existing statement of the same account" in {
      val result = check(List("2025-11-27", "2025-12-05"), List(november))

      result.map(_.kind) must be(Some(ConfirmWarningKind.RowsInOtherStatement))
      result.map(_.message).getOrElse("") must (include("2025-11-27") and include("2025-11-01 → 2025-11-30"))
      result.map(_.message).getOrElse("") must not include "2025-12-05"
    }

    "list each date once" in {
      val message = check(List("2025-11-27", "2025-11-27"), List(november)).map(_.message).getOrElse("")

      message.split("2025-11-27", -1).length - 1 must be(1)
    }

    "not fire for statements of a different card" in {
      val others = List(
        november.copy(accountLast4 = "9999"),
        november.copy(bankName = "Other Bank"),
        november.copy(productType = "credit"),
        november.copy(currency = "USD")
      )
      check(List("2025-11-27"), others) must be(None)
    }

    "not fire when no existing statement covers the row dates" in {
      check(List("2025-12-05", "2025-12-20"), List(november)) must be(None)
    }

    "not fire when there are no existing statements" in {
      check(List("2025-11-27"), Nil) must be(None)
    }

    "cap the listed dates at 5 and summarise the rest" in {
      val rows = (20 to 26).map(d => s"2025-11-$d").toList
      val message = check(rows, List(november)).map(_.message).getOrElse("")

      message must (include("2025-11-20") and include("2025-11-24") and include("+2 more"))
      message must (not include "2025-11-25" and not include "2025-11-26")
    }
  }
}
