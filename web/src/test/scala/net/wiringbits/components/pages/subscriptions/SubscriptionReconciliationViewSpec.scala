package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.{
  GetSubscriptionReconciliation,
  SubscriptionCharge,
  SubscriptionChargeSource,
  SubscriptionCycle,
  SubscriptionCycleStatus
}
import net.wiringbits.core.ReactTestDom
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.UUID

class SubscriptionReconciliationViewSpec extends AnyWordSpec with ReactTestDom {

  private def cycle(due: String, status: SubscriptionCycleStatus): SubscriptionCycle =
    SubscriptionCycle(
      dueDate = LocalDate.parse(due),
      termId = UUID.randomUUID(),
      amountMin = None,
      amountMax = None,
      status = status
    )

  private def onTime(on: String) =
    SubscriptionCycleStatus.Matched(
      SubscriptionCharge(
        source = SubscriptionChargeSource.Expense,
        id = UUID.randomUUID(),
        date = LocalDate.parse(on),
        amount = BigDecimal("15.00")
      ),
      late = false
    )

  private def reconciliation(cycles: List[SubscriptionCycle]) =
    GetSubscriptionReconciliation.Response.Subscription(
      subscriptionId = UUID.randomUUID(),
      cycles = cycles,
      manualPayments = Nil
    )

  "component" should {
    "render collapsed on-time cycles without throwing" in {
      noException should be thrownBy render(
        SubscriptionReconciliationView(
          reconciliation(List(cycle("2026-01-05", onTime("2026-01-05")), cycle("2026-02-05", onTime("2026-02-05")))),
          removeDisabled = false,
          onRemoveManualPayment = _ => ()
        )
      )
    }

    "render the on-time summary and the cycles inside it" in {
      render(
        SubscriptionReconciliationView(
          reconciliation(
            List(cycle("2026-01-05", onTime("2026-01-05")), cycle("2026-02-05", SubscriptionCycleStatus.Missing))
          ),
          removeDisabled = false,
          onRemoveManualPayment = _ => ()
        )
      )

      val text = container.textContent
      text must include(SubscriptionReconciliationLogic.onTimeSummary(1))
      text must include("2026-01-05")
    }
  }
}
