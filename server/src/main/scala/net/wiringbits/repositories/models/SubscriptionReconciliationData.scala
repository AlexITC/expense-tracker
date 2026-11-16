package net.wiringbits.repositories.models

import java.time.LocalDate
import java.util.UUID

/** Everything needed to reconcile a user's subscriptions, read in one transaction. */
case class SubscriptionReconciliationData(
    subscriptions: List[SubscriptionReconciliationData.SubscriptionWithTerms],
    manualPayments: List[SubscriptionManualPayment],
    expenses: List[SubscriptionReconciliationData.ExpenseCandidate],
    bankStatementRows: List[SubscriptionReconciliationData.BankStatementRowCandidate]
)

object SubscriptionReconciliationData {
  // terms are ordered by effectiveFrom.
  case class SubscriptionWithTerms(subscription: Subscription, terms: List[SubscriptionTerm])

  case class ExpenseCandidate(id: UUID, date: LocalDate, amount: BigDecimal, merchantId: Option[UUID])

  /** An unlinked, unignored debit row. `amount` is positive (money out), the negation of the stored row amount. */
  case class BankStatementRowCandidate(id: UUID, date: LocalDate, amount: BigDecimal)
}
