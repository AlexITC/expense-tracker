package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.BankStatementDraft

object BankStatementDraftReconciliation {

  /** Re-derive the reconciliation fields from the payload's balances and rows. */
  def recompute(payload: BankStatementDraft.Payload): BankStatementDraft.Payload = {
    val r = AiStatementExtractor.reconcile(
      productType = payload.metadata.productType.flatMap(AiStatementExtractor.parseProductType),
      openingBalance = payload.metadata.openingBalance,
      closingBalance = payload.metadata.closingBalance,
      payload.transactions.map(t => (t.amount, t.runningBalance))
    )
    // With no rows there is nothing to accept unverified.
    payload.copy(
      reconciled = r.reconciled,
      discrepancy = r.discrepancy,
      unverifiable = AiStatementExtractor.isTerminalUnverifiable(r.unverifiable, payload.transactions.size)
    )
  }
}
