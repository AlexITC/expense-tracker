package net.wiringbits.api.models.subscriptions

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

import java.time.LocalDate
import java.util.UUID

class SubscriptionCycleStatusSpec extends AnyWordSpec with Matchers {

  private def charge(source: SubscriptionChargeSource, on: String): SubscriptionCharge =
    SubscriptionCharge(source, UUID.randomUUID(), LocalDate.parse(on), BigDecimal("10.00"))

  private def roundTrip(status: SubscriptionCycleStatus): JsResult[SubscriptionCycleStatus] =
    Json.toJson(status).validate[SubscriptionCycleStatus]

  "format" should {
    "round-trip every variant" in {
      List(
        SubscriptionCycleStatus.Matched(charge(SubscriptionChargeSource.ManualPayment, "2026-02-10"), late = true),
        SubscriptionCycleStatus.Retried(
          List(
            charge(SubscriptionChargeSource.BankStatementRow, "2026-02-08"),
            charge(SubscriptionChargeSource.Expense, "2026-02-12")
          )
        ),
        SubscriptionCycleStatus.Missing,
        SubscriptionCycleStatus.Upcoming
      ).foreach { status =>
        roundTrip(status) must be(JsSuccess(status))
      }
    }

    "write a tagged object" in {
      val paid = charge(SubscriptionChargeSource.Expense, "2026-02-10")
      Json.toJson[SubscriptionCycleStatus](SubscriptionCycleStatus.Matched(paid, late = false)) must be(
        Json.obj("kind" -> "matched", "charge" -> paid, "late" -> false)
      )
      Json.toJson[SubscriptionCycleStatus](SubscriptionCycleStatus.Missing) must be(Json.obj("kind" -> "missing"))
    }

    "fail to read an unknown kind" in {
      Json.obj("kind" -> "paid").validate[SubscriptionCycleStatus].isError must be(true)
    }
  }
}
