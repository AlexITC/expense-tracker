package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.GetBankStatements
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
      processedRows: Int = 0
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
      processedRows = processedRows
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
}
