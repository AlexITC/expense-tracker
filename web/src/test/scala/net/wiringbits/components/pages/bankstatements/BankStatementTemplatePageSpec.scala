package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class BankStatementTemplatePageSpec extends AnyWordSpec {

  "amountColor" should {
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

  "metaComplete" should {
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

  private val syntheticMeta = BankStatementDraft.Metadata(
    bankName = Some("Test Bank"),
    productType = Some("debit"),
    currency = Some("USD"),
    accountLast4 = Some("0000"),
    periodStart = Some(LocalDate.of(2026, 1, 1)),
    periodEnd = Some(LocalDate.of(2026, 1, 31)),
    openingBalance = None,
    closingBalance = None
  )
  private val syntheticRow = BankStatementDraft.Transaction(
    date = LocalDate.of(2026, 1, 10),
    amount = BigDecimal(-40),
    description = "Test purchase",
    txnType = Some("purchase"),
    runningBalance = None
  )
  private val unverifiablePayload = BankStatementDraft.Payload(
    metadata = syntheticMeta,
    transactions = List(syntheticRow),
    reconciled = false,
    discrepancy = Some("No balances found"),
    model = "test-model",
    stage = None,
    unverifiable = true
  )
  private val discrepancyPayload = unverifiablePayload.copy(unverifiable = false, discrepancy = Some("Off by 10.00"))
  private val reconciledPayload = unverifiablePayload.copy(reconciled = true, unverifiable = false, discrepancy = None)

  "unverifiedAckAvailable" should {
    "be true for a terminal-unverifiable payload" in {
      BankStatementTemplatePage.unverifiedAckAvailable(unverifiablePayload) mustBe true
    }
    "be false when there are no transactions" in {
      BankStatementTemplatePage.unverifiedAckAvailable(unverifiablePayload.copy(transactions = Nil)) mustBe false
    }
    "be false for a discrepancy payload" in {
      BankStatementTemplatePage.unverifiedAckAvailable(discrepancyPayload) mustBe false
    }
    "be false for a reconciled payload" in {
      BankStatementTemplatePage.unverifiedAckAvailable(reconciledPayload) mustBe false
    }
  }

  "canConfirm" should {
    "allow a reconciled payload without acknowledgement" in {
      BankStatementTemplatePage.canConfirm(reconciledPayload, acked = false, busy = false) mustBe true
    }
    "block an unverifiable payload until acknowledged" in {
      BankStatementTemplatePage.canConfirm(unverifiablePayload, acked = false, busy = false) mustBe false
      BankStatementTemplatePage.canConfirm(unverifiablePayload, acked = true, busy = false) mustBe true
    }
    "block a discrepancy payload even when acknowledged" in {
      BankStatementTemplatePage.canConfirm(discrepancyPayload, acked = true, busy = false) mustBe false
    }
    "block while busy" in {
      BankStatementTemplatePage.canConfirm(reconciledPayload, acked = false, busy = true) mustBe false
    }
    "block when metadata is incomplete" in {
      val incomplete = reconciledPayload.copy(metadata = syntheticMeta.copy(currency = None))
      BankStatementTemplatePage.canConfirm(incomplete, acked = false, busy = false) mustBe false
    }
  }

  "acceptUnverifiedFlag" should {
    "be true only when the acknowledgement is available and given" in {
      BankStatementTemplatePage.acceptUnverifiedFlag(unverifiablePayload, acked = true) mustBe true
      BankStatementTemplatePage.acceptUnverifiedFlag(unverifiablePayload, acked = false) mustBe false
      BankStatementTemplatePage.acceptUnverifiedFlag(discrepancyPayload, acked = true) mustBe false
      BankStatementTemplatePage.acceptUnverifiedFlag(reconciledPayload, acked = true) mustBe false
    }
  }
}
