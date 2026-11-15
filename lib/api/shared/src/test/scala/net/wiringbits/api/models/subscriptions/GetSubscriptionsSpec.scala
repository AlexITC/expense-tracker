package net.wiringbits.api.models.subscriptions

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

import java.time.{Instant, LocalDate}
import java.util.UUID

class GetSubscriptionsSpec extends AnyWordSpec with Matchers {

  "response json" should {
    "round-trip a subscription with closed and open terms" in {
      val response = GetSubscriptions.Response(
        List(
          GetSubscriptions.Response.Subscription(
            id = UUID.randomUUID(),
            name = "Streaming",
            cadence = SubscriptionCadence.Monthly,
            intervalDays = None,
            intervalMonths = None,
            paymentMethod = SubscriptionPaymentMethod.DirectDebit,
            categoryId = None,
            terms = List(
              SubscriptionTerm(
                id = UUID.randomUUID(),
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = Some(LocalDate.of(2026, 1, 1)),
                amountMin = Some(BigDecimal("8.99")),
                amountMax = Some(BigDecimal("8.99"))
              ),
              SubscriptionTerm(
                id = UUID.randomUUID(),
                effectiveFrom = LocalDate.of(2026, 1, 1),
                effectiveTo = None,
                amountMin = None,
                amountMax = None
              )
            ),
            createdAt = Instant.parse("2025-01-01T10:00:00Z")
          )
        )
      )

      Json.toJson(response).as[GetSubscriptions.Response] mustBe response
    }
  }
}
