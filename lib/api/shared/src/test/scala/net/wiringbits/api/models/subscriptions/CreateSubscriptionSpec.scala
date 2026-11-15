package net.wiringbits.api.models.subscriptions

import net.wiringbits.common.models.{SubscriptionDescription, SubscriptionName}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

import java.time.LocalDate
import java.util.UUID

class CreateSubscriptionSpec extends AnyWordSpec with Matchers {

  private def requestJson(name: String, cadence: String, description: String = "Family plan"): JsValue =
    Json.parse(
      s"""
        |{
        |  "name": "$name",
        |  "cadence": "$cadence",
        |  "intervalDays": null,
        |  "intervalMonths": null,
        |  "amountMin": 9.99,
        |  "amountMax": 9.99,
        |  "paymentMethod": "DirectDebit",
        |  "categoryId": null,
        |  "description": "$description",
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
        intervalMonths = None,
        amountMin = Some(BigDecimal("9.99")),
        amountMax = Some(BigDecimal("12.50")),
        paymentMethod = SubscriptionPaymentMethod.CardPortal,
        categoryId = Some(UUID.randomUUID()),
        description = Some(SubscriptionDescription.trusted("Family plan")),
        startDate = LocalDate.of(2026, 1, 15)
      )
      val decoded = Json.toJson(request).as[CreateSubscription.Request]
      decoded.copy(name = request.name, description = request.description) mustBe request
      decoded.name.string mustBe "Streaming"
      decoded.description.map(_.string) mustBe Some("Family plan")
    }

    "round-trip an every-N-months request" in {
      val request = CreateSubscription.Request(
        name = SubscriptionName.trusted("Insurance"),
        cadence = SubscriptionCadence.EveryNMonths,
        intervalDays = None,
        intervalMonths = Some(3),
        amountMin = None,
        amountMax = None,
        paymentMethod = SubscriptionPaymentMethod.DirectDebit,
        categoryId = None,
        description = None,
        startDate = LocalDate.of(2026, 1, 31)
      )
      val decoded = Json.toJson(request).as[CreateSubscription.Request]
      decoded.copy(name = request.name) mustBe request
    }

    "decode null optional fields as None" in {
      val result = requestJson("Streaming", "Monthly").as[CreateSubscription.Request]
      result.intervalDays mustBe None
      result.intervalMonths mustBe None
      result.categoryId mustBe None
      result.cadence mustBe SubscriptionCadence.Monthly
    }

    "fail to decode an over-long name" in {
      val name = "x" * (SubscriptionName.MaxLength + 1)
      requestJson(name, "Monthly").validate[CreateSubscription.Request].isError mustBe true
    }

    "fail to decode an over-long description" in {
      val description = "x" * (SubscriptionDescription.MaxLength + 1)
      requestJson("Streaming", "Monthly", description).validate[CreateSubscription.Request].isError mustBe true
    }

    "fail to decode an unknown cadence" in {
      requestJson("Streaming", "Weekly").validate[CreateSubscription.Request].isError mustBe true
    }
  }
}
