package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.{
  SubscriptionCharge,
  SubscriptionChargeSource,
  SubscriptionCycle,
  SubscriptionCycleStatus,
  SubscriptionManualPayment
}

/** How prominently a reconciled cycle is shown. */
enum CycleTone {
  case Error
  case Warning
  case Upcoming
  case Settled
}

/** The cycles of a subscription split into those that need the user's attention and those paid on time. */
case class CycleSplit(attention: List[SubscriptionCycle], onTime: List[SubscriptionCycle])

object SubscriptionReconciliationLogic {

  /** On-time matched cycles are collapsed; everything else (missing, late, retried, upcoming) stays visible. */
  def split(cycles: List[SubscriptionCycle]): CycleSplit = {
    val (onTime, attention) = cycles.partition(c => isOnTime(c.status))
    CycleSplit(attention = attention, onTime = onTime)
  }

  private def isOnTime(status: SubscriptionCycleStatus): Boolean =
    status match {
      case SubscriptionCycleStatus.Matched(_, late) => !late
      case _ => false
    }

  def statusLabel(status: SubscriptionCycleStatus): String =
    status match {
      case SubscriptionCycleStatus.Matched(charge, true) => s"Late (charged ${charge.date})"
      case SubscriptionCycleStatus.Matched(_, false) => "Paid"
      case SubscriptionCycleStatus.Retried(attempts) => s"Retried (${attempts.size} attempts)"
      case SubscriptionCycleStatus.Missing => "Missing"
      case SubscriptionCycleStatus.Upcoming => "Upcoming"
    }

  def tone(status: SubscriptionCycleStatus): CycleTone =
    status match {
      case SubscriptionCycleStatus.Matched(_, true) => CycleTone.Warning
      case SubscriptionCycleStatus.Matched(_, false) => CycleTone.Settled
      case SubscriptionCycleStatus.Retried(_) => CycleTone.Warning
      case SubscriptionCycleStatus.Missing => CycleTone.Error
      case SubscriptionCycleStatus.Upcoming => CycleTone.Upcoming
    }

  def sourceLabel(source: SubscriptionChargeSource): String =
    source match {
      case SubscriptionChargeSource.Expense => "Expense"
      case SubscriptionChargeSource.BankStatementRow => "Bank statement"
      case SubscriptionChargeSource.ManualPayment => "Manual payment"
    }

  private def chargeLabel(charge: SubscriptionCharge): String =
    s"${sourceLabel(charge.source)} ${charge.amount.bigDecimal.toPlainString} on ${charge.date}"

  /** The due date followed by the charges credited to the cycle, if any. */
  def cycleDescription(cycle: SubscriptionCycle): String = {
    val charges = cycle.status match {
      case SubscriptionCycleStatus.Matched(charge, _) => List(charge)
      case SubscriptionCycleStatus.Retried(attempts) => attempts
      case SubscriptionCycleStatus.Missing | SubscriptionCycleStatus.Upcoming => Nil
    }
    (s"Due ${cycle.dueDate}" :: charges.map(chargeLabel)).mkString(" · ")
  }

  def onTimeSummary(count: Int): String =
    if (count == 1) "1 cycle paid on time" else s"$count cycles paid on time"

  def manualPaymentLabel(payment: SubscriptionManualPayment): String =
    (List(s"Paid ${payment.amount.bigDecimal.toPlainString} on ${payment.paidDate}") ++
      payment.note.map(_.string)).mkString(" · ")
}
