package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod, SubscriptionTerm}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.UUID

class SubscriptionStatusLogicSpec extends AnyWordSpec {

  private def date(s: String): LocalDate = LocalDate.parse(s)

  private def term(from: String, to: Option[String], amount: Option[String] = None): SubscriptionTerm =
    SubscriptionTerm(
      id = UUID.randomUUID(),
      effectiveFrom = date(from),
      effectiveTo = to.map(date),
      amountMin = amount.map(BigDecimal(_)),
      amountMax = amount.map(BigDecimal(_))
    )

  private def monthly(start: String, today: String): Either[SubscriptionStatusError, LocalDate] =
    SubscriptionStatusLogic.latestOccurrence(
      anchor = date(start),
      cadence = SubscriptionCadence.Monthly,
      intervalDays = None,
      intervalMonths = None,
      today = date(today)
    )

  "latestOccurrence" should {
    "return the anchor when it is in the future" in {
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
      def yearly(today: String) = SubscriptionStatusLogic.latestOccurrence(
        anchor = date("2024-02-29"),
        cadence = SubscriptionCadence.Yearly,
        intervalDays = None,
        intervalMonths = None,
        today = date(today)
      )
      yearly("2025-02-27") must be(Right(date("2024-02-29")))
      yearly("2025-03-01") must be(Right(date("2025-02-28")))
      yearly("2028-02-29") must be(Right(date("2028-02-29")))
    }

    "step every-N-days occurrences by the interval" in {
      def everyTwoWeeks(today: String) = SubscriptionStatusLogic.latestOccurrence(
        anchor = date("2026-01-01"),
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(14),
        intervalMonths = None,
        today = date(today)
      )
      everyTwoWeeks("2026-01-14") must be(Right(date("2026-01-01")))
      everyTwoWeeks("2026-01-15") must be(Right(date("2026-01-15")))
      everyTwoWeeks("2026-02-11") must be(Right(date("2026-01-29")))
    }

    "fail when an every-N-days cadence has no interval" in {
      SubscriptionStatusLogic
        .latestOccurrence(
          anchor = date("2026-01-01"),
          cadence = SubscriptionCadence.EveryNDays,
          intervalDays = None,
          intervalMonths = None,
          today = date("2026-02-01")
        ) must be(Left(SubscriptionStatusError.MissingInterval))
    }

    "fail when an every-N-days cadence has a non-positive interval" in {
      SubscriptionStatusLogic
        .latestOccurrence(
          anchor = date("2026-01-01"),
          cadence = SubscriptionCadence.EveryNDays,
          intervalDays = Some(0),
          intervalMonths = None,
          today = date("2026-02-01")
        ) must be(Left(SubscriptionStatusError.NonPositiveInterval))
    }
  }

  private def everyNMonths(start: String, n: Option[Int], today: String): Either[SubscriptionStatusError, LocalDate] =
    SubscriptionStatusLogic.latestOccurrence(
      anchor = date(start),
      cadence = SubscriptionCadence.EveryNMonths,
      intervalDays = None,
      intervalMonths = n,
      today = date(today)
    )

  "latestOccurrence for an every-N-months cadence" should {
    "keep a day-31 start on the month end every 3 months without drifting" in {
      everyNMonths(start = "2026-01-31", n = Some(3), today = "2026-04-29") must be(Right(date("2026-01-31")))
      everyNMonths(start = "2026-01-31", n = Some(3), today = "2026-04-30") must be(Right(date("2026-04-30")))
      everyNMonths(start = "2026-01-31", n = Some(3), today = "2026-07-31") must be(Right(date("2026-07-31")))
      everyNMonths(start = "2026-01-31", n = Some(3), today = "2026-10-31") must be(Right(date("2026-10-31")))
      everyNMonths(start = "2026-01-31", n = Some(3), today = "2026-12-15") must be(Right(date("2026-10-31")))
    }

    "clamp to the end of February every 2 months and return to day 31 afterwards" in {
      everyNMonths(start = "2025-12-31", n = Some(2), today = "2026-02-27") must be(Right(date("2025-12-31")))
      everyNMonths(start = "2025-12-31", n = Some(2), today = "2026-02-28") must be(Right(date("2026-02-28")))
      everyNMonths(start = "2025-12-31", n = Some(2), today = "2026-04-29") must be(Right(date("2026-02-28")))
      everyNMonths(start = "2025-12-31", n = Some(2), today = "2026-04-30") must be(Right(date("2026-04-30")))
      everyNMonths(start = "2025-12-31", n = Some(2), today = "2026-08-31") must be(Right(date("2026-08-31")))
    }

    "match the monthly cadence when the interval is 1" in {
      val todays =
        List("2026-01-31", "2026-02-27", "2026-02-28", "2026-03-30", "2026-03-31", "2026-04-30", "2027-01-15")
      todays.foreach { today =>
        everyNMonths(start = "2026-01-31", n = Some(1), today = today) must be(monthly(start = "2026-01-31", today))
      }
    }

    "fail without an interval" in {
      everyNMonths(start = "2026-01-01", n = None, today = "2026-05-01") must be(
        Left(SubscriptionStatusError.MissingIntervalMonths)
      )
    }

    "fail with a non-positive interval" in {
      everyNMonths(start = "2026-01-01", n = Some(0), today = "2026-05-01") must be(
        Left(SubscriptionStatusError.NonPositiveIntervalMonths)
      )
    }
  }

  "expectedDate" should {
    def monthlyExpected(terms: List[SubscriptionTerm], today: String) = SubscriptionStatusLogic.expectedDate(
      terms = terms,
      cadence = SubscriptionCadence.Monthly,
      intervalDays = None,
      intervalMonths = None,
      today = date(today)
    )

    "keep the billing day across a mid-cycle price change" in {
      val terms = List(term("2026-01-10", Some("2026-03-20")), term("2026-03-20", None))
      monthlyExpected(terms, today = "2026-03-15") must be(Right(Some(date("2026-03-10"))))
      monthlyExpected(terms, today = "2026-03-25") must be(Right(Some(date("2026-03-10"))))
      monthlyExpected(terms, today = "2026-04-25") must be(Right(Some(date("2026-04-10"))))
    }

    "keep the billing day across several adjacent terms" in {
      val terms = List(
        term("2026-01-10", Some("2026-02-15")),
        term("2026-02-15", Some("2026-03-20")),
        term("2026-03-20", None)
      )
      monthlyExpected(terms, today = "2026-05-12") must be(Right(Some(date("2026-05-10"))))
    }

    "keep the every-N-days phase across a price change" in {
      val terms = List(term("2026-01-01", Some("2026-01-20")), term("2026-01-20", None))
      SubscriptionStatusLogic.expectedDate(
        terms = terms,
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(14),
        intervalMonths = None,
        today = date("2026-02-01")
      ) must be(Right(Some(date("2026-01-29"))))
    }

    "re-anchor on the resume date after a gap" in {
      val terms = List(term("2026-01-10", Some("2026-03-01")), term("2026-05-20", None))
      monthlyExpected(terms, today = "2026-06-25") must be(Right(Some(date("2026-06-20"))))
    }

    "return the next term's start when today falls in a gap" in {
      val terms = List(term("2026-01-10", Some("2026-03-01")), term("2026-05-01", None))
      monthlyExpected(terms, today = "2026-04-15") must be(Right(Some(date("2026-05-01"))))
    }

    "return None when every term has ended" in {
      monthlyExpected(List(term("2026-01-10", Some("2026-03-01"))), today = "2026-04-15") must be(Right(None))
    }
  }

  "status" should {
    def monthlyStatus(terms: List[SubscriptionTerm], today: String) = SubscriptionStatusLogic.status(
      terms = terms,
      cadence = SubscriptionCadence.Monthly,
      intervalDays = None,
      intervalMonths = None,
      today = date(today)
    )

    "be upcoming when the only term starts in the future" in {
      monthlyStatus(List(term("2026-05-01", None)), today = "2026-04-20") must be(Right(SubscriptionStatus.Upcoming))
    }

    "be due when the expected date is today" in {
      monthlyStatus(List(term("2026-01-20", None)), today = "2026-04-20") must be(Right(SubscriptionStatus.Due))
    }

    "be overdue when the expected date is in the past" in {
      monthlyStatus(List(term("2026-01-10", None)), today = "2026-04-20") must be(Right(SubscriptionStatus.Overdue))
    }

    "be upcoming during a gap before a resumed term" in {
      val terms = List(term("2026-01-10", Some("2026-03-01")), term("2026-05-01", None))
      monthlyStatus(terms, today = "2026-04-20") must be(Right(SubscriptionStatus.Upcoming))
    }

    "be cancelled, never overdue, once every term has ended" in {
      val terms = List(term("2026-01-10", Some("2026-03-01")))
      monthlyStatus(terms, today = "2026-03-01") must be(Right(SubscriptionStatus.Cancelled))
      monthlyStatus(terms, today = "2026-08-25") must be(Right(SubscriptionStatus.Cancelled))
    }

    "not become due on a price change date that is not a billing day" in {
      val terms = List(term("2026-01-10", Some("2026-04-20")), term("2026-04-20", None))
      monthlyStatus(terms, today = "2026-04-20") must not be Right(SubscriptionStatus.Due)
      monthlyStatus(terms, today = "2026-05-10") must be(Right(SubscriptionStatus.Due))
    }

    "propagate a missing interval as an error" in {
      SubscriptionStatusLogic
        .status(
          terms = List(term("2026-01-01", None)),
          cadence = SubscriptionCadence.EveryNDays,
          intervalDays = None,
          intervalMonths = None,
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
      SubscriptionStatusLogic.cadenceLabel(
        SubscriptionCadence.Monthly,
        intervalDays = None,
        intervalMonths = None
      ) must be("Monthly")
      SubscriptionStatusLogic.cadenceLabel(
        SubscriptionCadence.Yearly,
        intervalDays = None,
        intervalMonths = None
      ) must be("Yearly")
    }

    "include the interval for every-N-days cadences" in {
      SubscriptionStatusLogic.cadenceLabel(
        SubscriptionCadence.EveryNDays,
        intervalDays = Some(14),
        intervalMonths = None
      ) must be("Every 14 days")
    }

    "include the interval for every-N-months cadences" in {
      SubscriptionStatusLogic.cadenceLabel(
        SubscriptionCadence.EveryNMonths,
        intervalDays = None,
        intervalMonths = Some(3)
      ) must be("Every 3 months")
      SubscriptionStatusLogic.cadenceLabel(
        SubscriptionCadence.EveryNMonths,
        intervalDays = None,
        intervalMonths = Some(1)
      ) must be("Every month")
    }
  }

  "paymentMethodLabel" should {
    "give a readable label for every payment method" in {
      SubscriptionStatusLogic.paymentMethodLabel(SubscriptionPaymentMethod.DirectDebit) must be("Direct debit")
      SubscriptionStatusLogic.paymentMethodLabel(SubscriptionPaymentMethod.ManualTransfer) must be("Manual transfer")
      SubscriptionStatusLogic.paymentMethodLabel(SubscriptionPaymentMethod.CardPortal) must be("Card portal")
      SubscriptionStatusLogic.paymentMethodLabel(SubscriptionPaymentMethod.Cash) must be("Cash")
    }
  }

  "coveringTerm" should {
    val closed = term("2026-01-01", Some("2026-03-01"))
    val open = term("2026-03-01", None)

    "pick the term whose range covers the date" in {
      SubscriptionStatusLogic.coveringTerm(List(closed, open), date("2026-02-15")) must be(Some(closed))
    }

    "treat the end date as exclusive" in {
      SubscriptionStatusLogic.coveringTerm(List(closed, open), date("2026-03-01")) must be(Some(open))
    }

    "return None before the first term starts" in {
      SubscriptionStatusLogic.coveringTerm(List(open), date("2026-02-01")) must be(None)
    }

    "return None when every term has ended" in {
      SubscriptionStatusLogic.coveringTerm(List(closed), date("2026-04-01")) must be(None)
    }
  }

  "expectedTerm" should {
    "switch the amount to the new price on its effective date" in {
      val old = term("2026-01-01", Some("2026-03-01"), Some("10.00"))
      val raised = term("2026-03-01", None, Some("12.00"))
      SubscriptionStatusLogic.expectedTerm(List(old, raised), date("2026-02-28")).flatMap(_.amountMin) must be(
        Some(BigDecimal("10.00"))
      )
      SubscriptionStatusLogic.expectedTerm(List(old, raised), date("2026-03-01")).flatMap(_.amountMin) must be(
        Some(BigDecimal("12.00"))
      )
    }

    "fall back to the next term when none covers the date" in {
      val future = term("2026-05-01", None, Some("9.99"))
      SubscriptionStatusLogic.expectedTerm(
        List(term("2026-01-01", Some("2026-02-01")), future),
        date("2026-03-01")
      ) must
        be(Some(future))
    }

    "return None for a cancelled subscription" in {
      SubscriptionStatusLogic.expectedTerm(List(term("2026-01-01", Some("2026-02-01"))), date("2026-03-01")) must be(
        None
      )
    }
  }
}
