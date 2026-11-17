package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.forms.DraftTransactionRow
import net.wiringbits.ui.forms.ExpSignedAmount
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

  "txnTypeOptions" should {
    "list the known types for a blank value" in {
      BankStatementTemplatePage.txnTypeOptions("") mustBe BankStatementTemplatePage.txnTypes
    }
    "list the known types for a known value" in {
      BankStatementTemplatePage.txnTypeOptions("fee") mustBe BankStatementTemplatePage.txnTypes
    }
    "append an unknown value so it stays selectable" in {
      BankStatementTemplatePage.txnTypeOptions("transfer") mustBe BankStatementTemplatePage.txnTypes :+ "transfer"
    }
  }

  private def seededRows: List[DraftTransactionRow] = DraftTransactionRow.fromTransactions(List(syntheticRow))

  private def withBadAmount(rows: List[DraftTransactionRow]): List[DraftTransactionRow] =
    DraftTransactionRow.update(
      rows,
      rows.head.key,
      r => r.copy(amount = r.amount.updated(ExpSignedAmount.validate("x")))
    )

  "editedPayload" should {
    "replace the transactions with the resolved rows" in {
      val edited = syntheticRow.copy(amount = BigDecimal("-12.34"))
      val rows = DraftTransactionRow.fromTransactions(List(edited))
      BankStatementTemplatePage.editedPayload(reconciledPayload, rows) mustBe
        Some(reconciledPayload.copy(transactions = List(edited)))
    }
    "be None while a row is invalid" in {
      BankStatementTemplatePage.editedPayload(reconciledPayload, withBadAmount(seededRows)) mustBe None
    }
    "be None while a new row is blank" in {
      BankStatementTemplatePage.editedPayload(
        reconciledPayload,
        DraftTransactionRow.insertAtTop(seededRows)
      ) mustBe None
    }
  }

  "canSave" should {
    "allow valid rows" in {
      BankStatementTemplatePage.canSave(seededRows, busy = false) mustBe true
    }
    "allow an empty row list" in {
      BankStatementTemplatePage.canSave(Nil, busy = false) mustBe true
    }
    "block invalid rows" in {
      BankStatementTemplatePage.canSave(withBadAmount(seededRows), busy = false) mustBe false
    }
    "block while busy" in {
      BankStatementTemplatePage.canSave(seededRows, busy = true) mustBe false
    }
  }

  "canConfirmEdited" should {
    "allow valid rows on a confirmable payload" in {
      BankStatementTemplatePage.canConfirmEdited(reconciledPayload, seededRows, acked = false, busy = false) mustBe true
    }
    "block invalid rows" in {
      BankStatementTemplatePage.canConfirmEdited(
        reconciledPayload,
        withBadAmount(seededRows),
        acked = false,
        busy = false
      ) mustBe false
    }
    "block the ack when the edited rows are empty, even if the saved payload has rows" in {
      val emptyEditedRows = List.empty[DraftTransactionRow]
      BankStatementTemplatePage.canConfirmEdited(
        unverifiablePayload,
        emptyEditedRows,
        acked = true,
        busy = false
      ) mustBe false
    }
    "accept the ack when the edited rows are non-empty, even if the saved payload has none" in {
      val savedWithoutRows = unverifiablePayload.copy(transactions = Nil)
      val nonEmptyEditedRows = seededRows
      BankStatementTemplatePage.canConfirmEdited(
        savedWithoutRows,
        nonEmptyEditedRows,
        acked = true,
        busy = false
      ) mustBe true
    }
  }

  "canInsertRow" should {
    "allow inserting into an empty list" in {
      BankStatementTemplatePage.canInsertRow(Nil, busy = false) mustBe true
    }
    "block while busy" in {
      BankStatementTemplatePage.canInsertRow(Nil, busy = true) mustBe false
    }
    "block at the row cap" in {
      val full = (0 until BankStatementDraftLimits.MaxTransactions).toList.map(DraftTransactionRow.blank)
      BankStatementTemplatePage.canInsertRow(full, busy = false) mustBe false
    }
  }
}
