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
}
