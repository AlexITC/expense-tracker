package net.wiringbits.api.models.subscriptions

import net.wiringbits.common.models.SubscriptionName
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

import java.time.LocalDate
import java.util.UUID

class CreateSubscriptionSpec extends AnyWordSpec with Matchers {

  private def requestJson(name: String, cadence: String): JsValue =
    Json.parse(
      s"""
        |{
        |  "name": "$name",
        |  "cadence": "$cadence",
        |  "intervalDays": null,
        |  "amountMin": 9.99,
        |  "amountMax": 9.99,
        |  "paymentMethod": "DirectDebit",
        |  "categoryId": null,
        |  "startDate": "2026-01-15"
        |}
        |""".stripMargin
    )

  "request json" should {
    "round-trip a valid request" in {
      val request = CreateSubscription.Request(
        name = SubscriptionName.trusted("Streaming"),
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(28),
        amountMin = Some(BigDecimal("9.99")),
        amountMax = Some(BigDecimal("12.50")),
        paymentMethod = SubscriptionPaymentMethod.CardPortal,
        categoryId = Some(UUID.randomUUID()),
        startDate = LocalDate.of(2026, 1, 15)
      )
      val decoded = Json.toJson(request).as[CreateSubscription.Request]
      decoded.copy(name = request.name) mustBe request
      decoded.name.string mustBe "Streaming"
    }

    "decode null optional fields as None" in {
      val result = requestJson("Streaming", "Monthly").as[CreateSubscription.Request]
      result.intervalDays mustBe None
      result.categoryId mustBe None
      result.cadence mustBe SubscriptionCadence.Monthly
    }

    "fail to decode an over-long name" in {
      val name = "x" * (SubscriptionName.MaxLength + 1)
      requestJson(name, "Monthly").validate[CreateSubscription.Request].isError mustBe true
    }

    "fail to decode an unknown cadence" in {
      requestJson("Streaming", "Weekly").validate[CreateSubscription.Request].isError mustBe true
    }
  }
}
