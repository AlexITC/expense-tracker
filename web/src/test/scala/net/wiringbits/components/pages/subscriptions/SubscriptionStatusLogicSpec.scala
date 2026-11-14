package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.SubscriptionCadence
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class SubscriptionStatusLogicSpec extends AnyWordSpec {

  private def date(s: String): LocalDate = LocalDate.parse(s)

  private def monthly(start: String, today: String): Either[SubscriptionStatusError, LocalDate] =
    SubscriptionStatusLogic.expectedDate(
      startDate = date(start),
      cadence = SubscriptionCadence.Monthly,
      intervalDays = None,
      today = date(today)
    )

  "expectedDate" should {
    "return the start date when it is in the future" in {
      monthly(start = "2026-03-10", today = "2026-03-01") must be(Right(date("2026-03-10")))
    }

    "return the latest monthly occurrence on or before today" in {
      monthly(start = "2026-01-15", today = "2026-04-20") must be(Right(date("2026-04-15")))
      monthly(start = "2026-01-15", today = "2026-04-14") must be(Right(date("2026-03-15")))
    }

    "clamp a day-31 start to the end of February and return to day 31 afterwards" in {
      monthly(start = "2026-01-31", today = "2026-02-28") must be(Right(date("2026-02-28")))
      monthly(start = "2026-01-31", today = "2026-03-30") must be(Right(date("2026-02-28")))
      monthly(start = "2026-01-31", today = "2026-03-31") must be(Right(date("2026-03-31")))
      monthly(start = "2026-01-31", today = "2026-04-30") must be(Right(date("2026-04-30")))
    }

    "clamp a leap-day yearly start to 28 February and return to 29 February in the next leap year" in {
      def yearly(today: String) = SubscriptionStatusLogic.expectedDate(
        startDate = date("2024-02-29"),
        cadence = SubscriptionCadence.Yearly,
        intervalDays = None,
        today = date(today)
      )
      yearly("2025-02-27") must be(Right(date("2024-02-29")))
      yearly("2025-03-01") must be(Right(date("2025-02-28")))
      yearly("2028-02-29") must be(Right(date("2028-02-29")))
    }

    "step every-N-days occurrences by the interval" in {
      def everyTwoWeeks(today: String) = SubscriptionStatusLogic.expectedDate(
        startDate = date("2026-01-01"),
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(14),
        today = date(today)
      )
      everyTwoWeeks("2026-01-14") must be(Right(date("2026-01-01")))
      everyTwoWeeks("2026-01-15") must be(Right(date("2026-01-15")))
      everyTwoWeeks("2026-02-11") must be(Right(date("2026-01-29")))
    }

    "fail when an every-N-days cadence has no interval" in {
      SubscriptionStatusLogic
        .expectedDate(
          startDate = date("2026-01-01"),
          cadence = SubscriptionCadence.EveryNDays,
          intervalDays = None,
          today = date("2026-02-01")
        ) must be(Left(SubscriptionStatusError.MissingInterval))
    }

    "fail when an every-N-days cadence has a non-positive interval" in {
      SubscriptionStatusLogic
        .expectedDate(
          startDate = date("2026-01-01"),
          cadence = SubscriptionCadence.EveryNDays,
          intervalDays = Some(0),
          today = date("2026-02-01")
        ) must be(Left(SubscriptionStatusError.NonPositiveInterval))
    }
  }

  "status" should {
    def monthlyStatus(start: String, today: String) = SubscriptionStatusLogic.status(
      startDate = date(start),
      cadence = SubscriptionCadence.Monthly,
      intervalDays = None,
      today = date(today)
    )

    "be upcoming when the start date is in the future" in {
      monthlyStatus(start = "2026-05-01", today = "2026-04-20") must be(Right(SubscriptionStatus.Upcoming))
    }

    "be due when the expected date is today" in {
      monthlyStatus(start = "2026-01-20", today = "2026-04-20") must be(Right(SubscriptionStatus.Due))
    }

    "be overdue when the expected date is in the past" in {
      monthlyStatus(start = "2026-01-10", today = "2026-04-20") must be(Right(SubscriptionStatus.Overdue))
    }

    "propagate a missing interval as an error" in {
      SubscriptionStatusLogic
        .status(
          startDate = date("2026-01-01"),
          cadence = SubscriptionCadence.EveryNDays,
          intervalDays = None,
          today = date("2026-02-01")
        ) must be(Left(SubscriptionStatusError.MissingInterval))
    }
  }

  "amountLabel" should {
    "show a single amount when min and max are equal" in {
      SubscriptionStatusLogic.amountLabel(Some(BigDecimal("12.50")), Some(BigDecimal("12.50"))) must be("12.50")
    }

    "show a range when min and max differ" in {
      SubscriptionStatusLogic.amountLabel(Some(BigDecimal("10.00")), Some(BigDecimal("25.00"))) must be(
        "10.00 - 25.00"
      )
    }

    "show plain notation for large amounts" in {
      SubscriptionStatusLogic.amountLabel(Some(BigDecimal("1E+3")), Some(BigDecimal("1E+3"))) must be("1000")
    }

    "be empty when the amount is unknown" in {
      SubscriptionStatusLogic.amountLabel(None, None) must be("")
    }
  }

  "cadenceLabel" should {
    "name monthly and yearly cadences" in {
      SubscriptionStatusLogic.cadenceLabel(SubscriptionCadence.Monthly, None) must be("Monthly")
      SubscriptionStatusLogic.cadenceLabel(SubscriptionCadence.Yearly, None) must be("Yearly")
    }

    "include the interval for every-N-days cadences" in {
      SubscriptionStatusLogic.cadenceLabel(SubscriptionCadence.EveryNDays, Some(14)) must be("Every 14 days")
    }
  }
}
