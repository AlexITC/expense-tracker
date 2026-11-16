package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionTerm}
import net.wiringbits.api.utils.SubscriptionSchedule.DueDate
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.UUID

class SubscriptionScheduleSpec extends AnyWordSpec with Matchers {

  private def date(s: String): LocalDate = LocalDate.parse(s)

  private def term(from: String, to: Option[String], amount: String = "10.00"): SubscriptionTerm =
    SubscriptionTerm(
      id = UUID.randomUUID(),
      effectiveFrom = date(from),
      effectiveTo = to.map(date),
      amountMin = Some(BigDecimal(amount)),
      amountMax = Some(BigDecimal(amount))
    )

  private def dueDates(
      terms: List[SubscriptionTerm],
      cadence: SubscriptionCadence,
      today: String,
      intervalDays: Option[Int] = None,
      intervalMonths: Option[Int] = None
  ): Either[SubscriptionScheduleError, List[DueDate]] =
    SubscriptionSchedule.dueDates(
      terms = terms,
      cadence = cadence,
      intervalDays = intervalDays,
      intervalMonths = intervalMonths,
      today = date(today)
    )

  private def dates(
      result: Either[SubscriptionScheduleError, List[DueDate]]
  ): Either[SubscriptionScheduleError, List[LocalDate]] =
    result.map(_.map(_.date))

  "dueDates" should {
    "clamp a monthly 31st charge without drifting" in {
      val result = dueDates(List(term("2026-01-31", None)), SubscriptionCadence.Monthly, today = "2026-04-15")
      dates(result) must be(Right(List("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30").map(date)))
    }

    "keep a yearly charge from 29 Feb on the leap day" in {
      val result = dueDates(List(term("2024-02-29", None)), SubscriptionCadence.Yearly, today = "2027-03-01")
      dates(result) must be(Right(List("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29").map(date)))
    }

    "step every 2 months" in {
      val result = dueDates(
        List(term("2026-01-31", None)),
        SubscriptionCadence.EveryNMonths,
        today = "2026-06-01",
        intervalMonths = Some(2)
      )
      dates(result) must be(Right(List("2026-01-31", "2026-03-31", "2026-05-31", "2026-07-31").map(date)))
    }

    "step every 3 months" in {
      val result = dueDates(
        List(term("2025-11-30", None)),
        SubscriptionCadence.EveryNMonths,
        today = "2026-05-30",
        intervalMonths = Some(3)
      )
      dates(result) must be(Right(List("2025-11-30", "2026-02-28", "2026-05-30", "2026-08-30").map(date)))
    }

    "step every N days" in {
      val result = dueDates(
        List(term("2026-01-01", None)),
        SubscriptionCadence.EveryNDays,
        today = "2026-01-29",
        intervalDays = Some(14)
      )
      dates(result) must be(Right(List("2026-01-01", "2026-01-15", "2026-01-29", "2026-02-12").map(date)))
    }

    "keep the anchor across a price change and attach each date to its covering term" in {
      val first = term("2026-01-31", Some("2026-03-15"), amount = "10.00")
      val second = term("2026-03-15", None, amount = "12.00")
      val result = dueDates(List(second, first), SubscriptionCadence.Monthly, today = "2026-04-01")
      result must be(
        Right(
          List(
            DueDate(date("2026-01-31"), first),
            DueDate(date("2026-02-28"), first),
            DueDate(date("2026-03-31"), second),
            DueDate(date("2026-04-30"), second)
          )
        )
      )
    }

    "produce no dates inside a cancel/resume gap and re-anchor at the resumed term" in {
      val before = term("2026-01-10", Some("2026-02-20"))
      val after = term("2026-05-05", None)
      val result = dueDates(List(before, after), SubscriptionCadence.Monthly, today = "2026-06-10")
      result must be(
        Right(
          List(
            DueDate(date("2026-01-10"), before),
            DueDate(date("2026-02-10"), before),
            DueDate(date("2026-05-05"), after),
            DueDate(date("2026-06-05"), after),
            DueDate(date("2026-07-05"), after)
          )
        )
      )
    }

    "return no upcoming date after a cancelled subscription ends" in {
      val result = dueDates(List(term("2026-01-10", Some("2026-02-20"))), SubscriptionCadence.Monthly, "2026-06-10")
      dates(result) must be(Right(List("2026-01-10", "2026-02-10").map(date)))
    }

    "return only the upcoming date for a future-only term" in {
      val future = term("2026-09-01", None)
      val result = dueDates(List(future), SubscriptionCadence.Monthly, today = "2026-06-10")
      result must be(Right(List(DueDate(date("2026-09-01"), future))))
    }

    "fail when the every-N-days interval is missing" in {
      dueDates(List(term("2026-01-01", None)), SubscriptionCadence.EveryNDays, today = "2026-02-01") must be(
        Left(SubscriptionScheduleError.MissingInterval)
      )
    }

    "fail when the every-N-months interval is missing" in {
      dueDates(List(term("2026-01-01", None)), SubscriptionCadence.EveryNMonths, today = "2026-02-01") must be(
        Left(SubscriptionScheduleError.MissingIntervalMonths)
      )
    }

    "fail when the interval is not positive" in {
      dueDates(
        List(term("2026-01-01", None)),
        SubscriptionCadence.EveryNDays,
        today = "2026-02-01",
        intervalDays = Some(0)
      ) must be(Left(SubscriptionScheduleError.NonPositiveInterval))
    }

    "fail when the every-N-months interval is not positive" in {
      dueDates(
        List(term("2026-01-01", None)),
        SubscriptionCadence.EveryNMonths,
        today = "2026-02-01",
        intervalMonths = Some(0)
      ) must be(Left(SubscriptionScheduleError.NonPositiveIntervalMonths))
    }

    "not produce a due date on the day a cancelled term ends" in {
      val cancelled = term("2026-01-10", Some("2026-03-10"))
      val result = dueDates(List(cancelled), SubscriptionCadence.Monthly, today = "2026-04-01")
      result must be(Right(List(DueDate(date("2026-01-10"), cancelled), DueDate(date("2026-02-10"), cancelled))))
    }

    "attach a due date on the price change day to the new term" in {
      val first = term("2026-01-10", Some("2026-03-10"), amount = "10.00")
      val second = term("2026-03-10", None, amount = "12.00")
      val result = dueDates(List(first, second), SubscriptionCadence.Monthly, today = "2026-03-15")
      result must be(
        Right(
          List(
            DueDate(date("2026-01-10"), first),
            DueDate(date("2026-02-10"), first),
            DueDate(date("2026-03-10"), second),
            DueDate(date("2026-04-10"), second)
          )
        )
      )
    }

    "return the resumed anchor as the upcoming date when a resume starts after today" in {
      val before = term("2026-01-10", Some("2026-02-20"))
      val after = term("2026-09-01", None)
      val result = dueDates(List(before, after), SubscriptionCadence.Monthly, today = "2026-06-10")
      result must be(
        Right(
          List(
            DueDate(date("2026-01-10"), before),
            DueDate(date("2026-02-10"), before),
            DueDate(date("2026-09-01"), after)
          )
        )
      )
    }

    "keep only the latest occurrences of an ancient daily subscription" in {
      val ancient = term("1000-01-01", None)
      val result = dueDates(List(ancient), SubscriptionCadence.EveryNDays, today = "2026-06-10", intervalDays = Some(1))
      val max = SubscriptionSchedule.MaxPastOccurrences
      dates(result) must be(Right((0 to max).map(i => date("2026-06-10").minusDays(max - 1L - i)).toList))
    }

    "keep the latest occurrences of a daily run that ended long before today" in {
      val ended = term("2020-01-01", Some("2023-01-01"))
      val result = dueDates(List(ended), SubscriptionCadence.EveryNDays, today = "2026-06-10", intervalDays = Some(1))
      val max = SubscriptionSchedule.MaxPastOccurrences
      dates(result) must be(Right((1 to max).map(i => date("2023-01-01").minusDays(max + 1L - i)).toList))
    }
  }
}
