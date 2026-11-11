package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.GetBankStatements
import net.wiringbits.api.models.documents.DocumentStatus
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementListSpec extends AnyWordSpec {

  private def stmt(
      bankName: String = "BBVA",
      productType: String = "credit",
      currency: String = "EUR",
      accountLast4: String = "1234",
      periodStart: LocalDate = LocalDate.of(2026, 4, 1),
      periodEnd: LocalDate = LocalDate.of(2026, 4, 30),
      accountAlias: Option[String] = None
  ): GetBankStatements.Response.BankStatement =
    GetBankStatements.Response.BankStatement(
      id = UUID.randomUUID(),
      documentFilename = "s.pdf",
      documentStatus = DocumentStatus.Parsed,
      bankName = bankName,
      productType = productType,
      currency = currency,
      accountLast4 = accountLast4,
      periodStart = periodStart,
      periodEnd = periodEnd,
      createdAt = Instant.now(),
      totalRows = 0,
      processedRows = 0,
      overlappingStatementIds = Nil,
      accountAlias = accountAlias
    )

  "accountKeyOf" should {
    "build a key from bank + product type + last 4" in {
      BankStatementList.accountKeyOf(stmt(bankName = "BBVA", productType = "credit", accountLast4 = "1234")) mustBe
        "BBVA~credit~1234"
    }
  }

  "accountLabelOf" should {
    "render bank, product label in parentheses and last 4" in {
      BankStatementList.accountLabelOf(stmt(bankName = "BBVA", productType = "credit", accountLast4 = "8927")) mustBe
        "BBVA (Credit) ••••8927"
    }

    "pass through unknown product types" in {
      BankStatementList.accountLabelOf(stmt(bankName = "BBVA", productType = "savings", accountLast4 = "8927")) mustBe
        "BBVA (savings) ••••8927"
    }

    "use the account alias" in {
      val s = stmt(bankName = "Example Bank", accountLast4 = "1111", accountAlias = Some("Travel card"))
      BankStatementList.accountLabelOf(s) mustBe "Travel card (Example Bank (Credit) ••••1111)"
    }
  }

  "metadataLine" should {
    "join the metadata fields" in {
      BankStatementList.metadataLine(
        stmt(
          bankName = "BBVA",
          productType = "credit",
          currency = "EUR",
          accountLast4 = "1234",
          periodStart = LocalDate.of(2026, 4, 1),
          periodEnd = LocalDate.of(2026, 4, 30)
        )
      ) mustBe "BBVA · Credit · EUR · ••••1234 · 2026-04-01 → 2026-04-30"
    }
  }

  "gapWarning" should {
    "warn when more than 20 days separate the newer start from the older end" in {
      val older = stmt(periodStart = LocalDate.of(2026, 2, 1), periodEnd = LocalDate.of(2026, 2, 28))
      val newer = stmt(periodStart = LocalDate.of(2026, 4, 1), periodEnd = LocalDate.of(2026, 4, 30))
      BankStatementList.gapWarning(newer, older).isDefined mustBe true
    }

    "not warn for consecutive cycles" in {
      val older = stmt(periodStart = LocalDate.of(2026, 3, 1), periodEnd = LocalDate.of(2026, 3, 31))
      val newer = stmt(periodStart = LocalDate.of(2026, 4, 1), periodEnd = LocalDate.of(2026, 4, 30))
      BankStatementList.gapWarning(newer, older) mustBe None
    }
  }
}
