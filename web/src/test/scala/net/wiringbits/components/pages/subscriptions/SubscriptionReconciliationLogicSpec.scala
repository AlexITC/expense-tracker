package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.{
  SubscriptionCharge,
  SubscriptionChargeSource,
  SubscriptionCycle,
  SubscriptionCycleStatus,
  SubscriptionManualPayment
}
import net.wiringbits.common.models.SubscriptionManualPaymentNote
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.UUID

class SubscriptionReconciliationLogicSpec extends AnyWordSpec {

  private def date(s: String): LocalDate = LocalDate.parse(s)

  private def charge(
      on: String,
      amount: String = "15.00",
      source: SubscriptionChargeSource = SubscriptionChargeSource.Expense
  ) =
    SubscriptionCharge(source = source, id = UUID.randomUUID(), date = date(on), amount = BigDecimal(amount))

  private def cycle(due: String, status: SubscriptionCycleStatus): SubscriptionCycle =
    SubscriptionCycle(
      dueDate = date(due),
      termId = UUID.randomUUID(),
      amountMin = None,
      amountMax = None,
      status = status
    )

  private val onTime = SubscriptionCycleStatus.Matched(charge("2026-01-05"), late = false)
  private val late = SubscriptionCycleStatus.Matched(charge("2026-01-08"), late = true)
  private val retried = SubscriptionCycleStatus.Retried(List(charge("2026-01-05"), charge("2026-01-07")))

  "split" should {
    "collapse only on-time matched cycles and keep the rest in order" in {
      val cycles = List(
        cycle("2026-01-05", onTime),
        cycle("2026-02-05", SubscriptionCycleStatus.Missing),
        cycle("2026-03-05", late),
        cycle("2026-04-05", onTime),
        cycle("2026-05-05", retried),
        cycle("2026-06-05", SubscriptionCycleStatus.Upcoming)
      )

      val result = SubscriptionReconciliationLogic.split(cycles)

      result.onTime.map(_.dueDate) must be(List(date("2026-01-05"), date("2026-04-05")))
      result.attention.map(_.dueDate) must be(
        List(date("2026-02-05"), date("2026-03-05"), date("2026-05-05"), date("2026-06-05"))
      )
    }

    "return empty lists for no cycles" in {
      SubscriptionReconciliationLogic.split(Nil) must be(CycleSplit(attention = Nil, onTime = Nil))
    }
  }

  "statusLabel" should {
    "label each status" in {
      SubscriptionReconciliationLogic.statusLabel(SubscriptionCycleStatus.Missing) must be("Missing")
      SubscriptionReconciliationLogic.statusLabel(SubscriptionCycleStatus.Upcoming) must be("Upcoming")
      SubscriptionReconciliationLogic.statusLabel(late) must be("Late (charged 2026-01-08)")
      SubscriptionReconciliationLogic.statusLabel(retried) must be("Retried (2 attempts)")
      SubscriptionReconciliationLogic.statusLabel(onTime) must be("Paid")
    }
  }

  "tone" should {
    "use error for missing, warning for late and retried, upcoming tint and settled for on time" in {
      SubscriptionReconciliationLogic.tone(SubscriptionCycleStatus.Missing) must be(CycleTone.Error)
      SubscriptionReconciliationLogic.tone(late) must be(CycleTone.Warning)
      SubscriptionReconciliationLogic.tone(retried) must be(CycleTone.Warning)
      SubscriptionReconciliationLogic.tone(SubscriptionCycleStatus.Upcoming) must be(CycleTone.Upcoming)
      SubscriptionReconciliationLogic.tone(onTime) must be(CycleTone.Settled)
    }
  }

  "cycleDescription" should {
    "show only the due date when nothing was charged" in {
      SubscriptionReconciliationLogic.cycleDescription(cycle("2026-02-05", SubscriptionCycleStatus.Missing)) must be(
        "Due 2026-02-05"
      )
    }

    "list the matched charge with its source" in {
      val status = SubscriptionCycleStatus.Matched(
        charge("2026-02-06", amount = "9.99", source = SubscriptionChargeSource.BankStatementRow),
        late = true
      )
      SubscriptionReconciliationLogic.cycleDescription(cycle("2026-02-05", status)) must be(
        "Due 2026-02-05 · Bank statement 9.99 on 2026-02-06"
      )
    }

    "list every retry attempt" in {
      SubscriptionReconciliationLogic.cycleDescription(cycle("2026-01-05", retried)) must be(
        "Due 2026-01-05 · Expense 15.00 on 2026-01-05 · Expense 15.00 on 2026-01-07"
      )
    }
  }

  "onTimeSummary" should {
    "use the singular for one cycle" in {
      SubscriptionReconciliationLogic.onTimeSummary(1) must be("1 cycle paid on time")
    }

    "use the plural otherwise" in {
      SubscriptionReconciliationLogic.onTimeSummary(3) must be("3 cycles paid on time")
    }
  }

  "manualPaymentLabel" should {
    "include the note when present" in {
      val payment = SubscriptionManualPayment(
        id = UUID.randomUUID(),
        paidDate = date("2026-03-01"),
        amount = BigDecimal("20.00"),
        note = Some(SubscriptionManualPaymentNote.trusted("Paid in cash"))
      )
      SubscriptionReconciliationLogic.manualPaymentLabel(payment) must be("Paid 20.00 on 2026-03-01 · Paid in cash")
    }

    "omit the note when absent" in {
      val payment = SubscriptionManualPayment(
        id = UUID.randomUUID(),
        paidDate = date("2026-03-01"),
        amount = BigDecimal("20.00"),
        note = None
      )
      SubscriptionReconciliationLogic.manualPaymentLabel(payment) must be("Paid 20.00 on 2026-03-01")
    }
  }
}
