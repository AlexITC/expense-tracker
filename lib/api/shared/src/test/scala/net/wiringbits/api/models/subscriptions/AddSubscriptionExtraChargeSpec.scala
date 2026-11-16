package net.wiringbits.api.models.subscriptions

import net.wiringbits.common.models.SubscriptionExtraChargeLabel
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

import java.time.LocalDate

class AddSubscriptionExtraChargeSpec extends AnyWordSpec with Matchers {

  private def requestJson(label: String, recurrence: String): JsValue = Json.obj(
    "label" -> label,
    "amount" -> 12.5,
    "recurrence" -> recurrence,
    "dueDate" -> "2025-03-01"
  )

  "request json" should {
    "decode a valid request with a trimmed label" in {
      val result = requestJson("  Yearly fee ", "Yearly").as[AddSubscriptionExtraCharge.Request]

      result.label.string mustBe "Yearly fee"
      result.amount mustBe BigDecimal("12.5")
      result.recurrence mustBe SubscriptionExtraChargeRecurrence.Yearly
      result.dueDate mustBe LocalDate.of(2025, 3, 1)
    }

    "fail to decode a blank label" in {
      requestJson("   ", "OneOff").validate[AddSubscriptionExtraCharge.Request].isError mustBe true
    }

    "fail to decode an over-long label" in {
      val label = "x" * (SubscriptionExtraChargeLabel.MaxLength + 1)
      requestJson(label, "OneOff").validate[AddSubscriptionExtraCharge.Request].isError mustBe true
    }

    "fail to decode an unknown recurrence" in {
      requestJson("Setup fee", "Weekly").validate[AddSubscriptionExtraCharge.Request].isError mustBe true
    }
  }
}
