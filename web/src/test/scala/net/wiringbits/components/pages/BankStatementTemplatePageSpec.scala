package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class BankStatementTemplatePageSpec extends AnyWordSpec {

  "BankStatementTemplatePage.amountColor" should {
    "return brand color for zero" in {
      BankStatementTemplatePage.amountColor(BigDecimal(0)) mustBe "var(--lc-brand)"
    }
    "return brand color for positive amount" in {
      BankStatementTemplatePage.amountColor(BigDecimal("100.50")) mustBe "var(--lc-brand)"
    }
    "return error color for negative amount" in {
      BankStatementTemplatePage.amountColor(BigDecimal("-0.01")) mustBe "var(--lc-error)"
    }
  }

  "BankStatementTemplatePage.metaComplete" should {
    val full = BankStatementDraft.Metadata(
      bankName = Some("BBVA"),
      productType = Some("debit"),
      currency = Some("EUR"),
      accountLast4 = Some("1234"),
      periodStart = Some(LocalDate.of(2026, 1, 1)),
      periodEnd = Some(LocalDate.of(2026, 1, 31)),
      openingBalance = None,
      closingBalance = None
    )

    "be true when every required field is present" in {
      BankStatementTemplatePage.metaComplete(full) mustBe true
    }
    "be false when the bank name is blank" in {
      BankStatementTemplatePage.metaComplete(full.copy(bankName = Some(" "))) mustBe false
    }
    "be false when the period is missing" in {
      BankStatementTemplatePage.metaComplete(full.copy(periodEnd = None)) mustBe false
    }
  }
}
