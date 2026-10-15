package net.wiringbits.components.pages

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
      periodEnd: LocalDate = LocalDate.of(2026, 4, 30)
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
      processedRows = 0
    )

  "BankStatementList.accountKeyOf" should {
    "build a key from bank + product type + last 4" in {
      BankStatementList.accountKeyOf(stmt(bankName = "BBVA", productType = "credit", accountLast4 = "1234")) mustBe
        "BBVA~credit~1234"
    }
  }

  "BankStatementList.metadataLine" should {
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

}
