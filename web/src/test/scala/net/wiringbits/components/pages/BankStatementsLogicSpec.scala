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
      productType: String = "credit"
  ): GetBankStatements.Response.BankStatement =
    GetBankStatements.Response.BankStatement(
      id = UUID.randomUUID(),
      documentFilename = "s.pdf",
      documentStatus = DocumentStatus.Parsed,
      bankName = bankName,
      productType = productType,
      currency = "EUR",
      accountLast4 = accountLast4,
      periodStart = LocalDate.of(2026, 4, 1),
      periodEnd = LocalDate.of(2026, 4, 30),
      createdAt = createdAt,
      totalRows = 0,
      processedRows = 0
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
}
