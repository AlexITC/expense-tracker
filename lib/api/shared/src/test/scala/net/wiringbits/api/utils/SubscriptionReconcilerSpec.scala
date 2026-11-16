package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.*
import net.wiringbits.api.models.subscriptions.SubscriptionCycleStatus.*
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.UUID

class SubscriptionReconcilerSpec extends AnyWordSpec with Matchers {

  private val tolerance = 5
  private val gymMerchant = UUID.randomUUID()
  private val streamingMerchant = UUID.randomUUID()

  private def date(s: String): LocalDate = LocalDate.parse(s)

  private def term(from: String, to: Option[String] = None, amount: Option[String] = Some("10.00")): SubscriptionTerm =
    SubscriptionTerm(
      id = UUID.randomUUID(),
      effectiveFrom = date(from),
      effectiveTo = to.map(date),
      amountMin = amount.map(BigDecimal(_)),
      amountMax = amount.map(BigDecimal(_))
    )

  private def rangeTerm(from: String, min: Option[String], max: Option[String]): SubscriptionTerm =
    SubscriptionTerm(
      id = UUID.randomUUID(),
      effectiveFrom = date(from),
      effectiveTo = None,
      amountMin = min.map(BigDecimal(_)),
      amountMax = max.map(BigDecimal(_))
    )

  private def subscription(
      terms: List[SubscriptionTerm],
      cadence: SubscriptionCadence = SubscriptionCadence.Monthly,
      intervalDays: Option[Int] = None,
      intervalMonths: Option[Int] = None,
      merchantId: Option[UUID] = None
  ): ReconcilerInput.Subscription =
    ReconcilerInput.Subscription(
      id = UUID.randomUUID(),
      cadence = cadence,
      intervalDays = intervalDays,
      intervalMonths = intervalMonths,
      merchantId = merchantId,
      terms = terms
    )

  private def expense(on: String, amount: String = "10.00", merchantId: Option[UUID] = None): ReconcilerInput.Charge =
    ReconcilerInput.Charge(
      SubscriptionChargeSource.Expense,
      UUID.randomUUID(),
      date(on),
      BigDecimal(amount),
      merchantId
    )

  private def bankRow(on: String, amount: String = "10.00"): ReconcilerInput.Charge =
    ReconcilerInput.Charge(
      SubscriptionChargeSource.BankStatementRow,
      UUID.randomUUID(),
      date(on),
      BigDecimal(amount),
      None
    )

  private def manual(sub: ReconcilerInput.Subscription, on: String, amount: String = "10.00") =
    ReconcilerInput.ManualPayment(
      subscriptionId = sub.id,
      payment = SubscriptionManualPayment(UUID.randomUUID(), date(on), BigDecimal(amount), note = None)
    )

  private def charge(c: ReconcilerInput.Charge): SubscriptionCharge =
    SubscriptionCharge(c.source, c.id, c.date, c.amount)

  private def manualCharge(p: ReconcilerInput.ManualPayment): SubscriptionCharge =
    SubscriptionCharge(SubscriptionChargeSource.ManualPayment, p.payment.id, p.payment.paidDate, p.payment.amount)

  private def reconcile(
      subscriptions: List[ReconcilerInput.Subscription],
      candidates: List[ReconcilerInput.Charge],
      today: String,
      manualPayments: List[ReconcilerInput.ManualPayment] = List.empty
  ): Map[UUID, GetSubscriptionReconciliation.Response.Subscription] =
    SubscriptionReconciler
      .reconcile(subscriptions, manualPayments, candidates, date(today), tolerance)
      .fold(e => fail(e.message), _.map(s => s.subscriptionId -> s).toMap)

  private def statuses(
      result: Map[UUID, GetSubscriptionReconciliation.Response.Subscription],
      sub: ReconcilerInput.Subscription
  ): List[(LocalDate, SubscriptionCycleStatus)] =
    result(sub.id).cycles.map(c => c.dueDate -> c.status)

  "reconcile" should {
    "match charges paid on the due date" in {
      val gym = subscription(List(term("2026-01-10")))
      val jan = expense("2026-01-10")
      val feb = bankRow("2026-02-10")
      val result = reconcile(List(gym), List(jan, feb), today = "2026-03-01")
      statuses(result, gym) must be(
        List(
          date("2026-01-10") -> Matched(charge(jan), late = false),
          date("2026-02-10") -> Matched(charge(feb), late = false),
          date("2026-03-10") -> Upcoming
        )
      )
    }

    "flag a charge inside the window but after the due date as late" in {
      val gym = subscription(List(term("2026-02-10")))
      val feb = expense("2026-02-15")
      statuses(reconcile(List(gym), List(feb), today = "2026-03-01"), gym).head must be(
        date("2026-02-10") -> Matched(charge(feb), late = true)
      )
    }

    "mark a cycle missing when the only charge is one day past the window" in {
      val gym = subscription(List(term("2026-02-10")))
      statuses(reconcile(List(gym), List(expense("2026-02-16")), today = "2026-03-01"), gym).head must be(
        date("2026-02-10") -> Missing
      )
    }

    "keep a cycle upcoming while its window is open and mark it missing the day after" in {
      val gym = subscription(List(term("2026-02-10")))
      statuses(reconcile(List(gym), List.empty, today = "2026-02-15"), gym).head must be(date("2026-02-10") -> Upcoming)
      statuses(reconcile(List(gym), List.empty, today = "2026-02-16"), gym).head must be(date("2026-02-10") -> Missing)
    }

    "report several charges for one cycle as retried, ordered by date" in {
      val gym = subscription(List(term("2026-02-10")))
      val failed = bankRow("2026-02-08")
      val paid = bankRow("2026-02-12")
      statuses(reconcile(List(gym), List(paid, failed), today = "2026-03-01"), gym).head must be(
        date("2026-02-10") -> Retried(List(charge(failed), charge(paid)))
      )
    }

    "resolve a same-amount collision by the merchant hint" in {
      val gym = subscription(List(term("2026-02-10")), merchantId = Some(gymMerchant))
      val streaming = subscription(List(term("2026-02-10")))
      val gymExpense = expense("2026-02-10", merchantId = Some(gymMerchant))
      val row = bankRow("2026-02-10")
      val result = reconcile(List(streaming, gym), List(row, gymExpense), today = "2026-02-20")
      statuses(result, gym).head must be(date("2026-02-10") -> Matched(charge(gymExpense), late = false))
      statuses(result, streaming).head must be(date("2026-02-10") -> Matched(charge(row), late = false))
    }

    "resolve a same-amount collision by the closest date" in {
      val gym = subscription(List(term("2026-02-10")))
      val streaming = subscription(List(term("2026-02-12")))
      val first = bankRow("2026-02-10")
      val second = bankRow("2026-02-12")
      val result = reconcile(List(streaming, gym), List(second, first), today = "2026-02-25")
      statuses(result, gym).head must be(date("2026-02-10") -> Matched(charge(first), late = false))
      statuses(result, streaming).head must be(date("2026-02-12") -> Matched(charge(second), late = false))
    }

    "never use one charge for two cycles of a subscription" in {
      val gym = subscription(List(term("2026-02-01")), cadence = SubscriptionCadence.EveryNDays, intervalDays = Some(7))
      val row = bankRow("2026-02-04")
      statuses(reconcile(List(gym), List(row), today = "2026-02-20"), gym).take(2) must be(
        List(date("2026-02-01") -> Matched(charge(row), late = true), date("2026-02-08") -> Missing)
      )
    }

    "never use one charge for two subscriptions" in {
      val gym = subscription(List(term("2026-02-10")))
      val streaming = subscription(List(term("2026-02-10")))
      val row = bankRow("2026-02-10")
      val result = reconcile(List(gym, streaming), List(row), today = "2026-02-20")
      val firstStatuses = List(gym, streaming).map(s => statuses(result, s).head._2)
      firstStatuses.count(_ == Matched(charge(row), late = false)) must be(1)
      firstStatuses.count(_ == Missing) must be(1)
    }

    "create no cycles inside a cancel/resume gap and leave a charge there unused" in {
      val gym = subscription(List(term("2026-01-10", to = Some("2026-03-01")), term("2026-05-20")))
      val jan = bankRow("2026-01-10")
      val feb = bankRow("2026-02-10")
      val may = bankRow("2026-05-20")
      // Within tolerance of the 2026-03-10 occurrence that the gap suppresses.
      val nearCancelled = bankRow("2026-03-12")
      val result = reconcile(List(gym), List(jan, feb, nearCancelled, may), today = "2026-06-01")
      statuses(result, gym) must be(
        List(
          date("2026-01-10") -> Matched(charge(jan), late = false),
          date("2026-02-10") -> Matched(charge(feb), late = false),
          date("2026-05-20") -> Matched(charge(may), late = false),
          date("2026-06-20") -> Upcoming
        )
      )
    }

    "keep a cycle after a gap missing rather than taking a charge from inside the gap" in {
      val gym = subscription(List(term("2026-01-10", to = Some("2026-02-01")), term("2026-04-20")))
      val result = reconcile(List(gym), List(bankRow("2026-04-14")), today = "2026-05-01")
      statuses(result, gym).map(_._2) must be(List(Missing, Missing, Upcoming))
    }

    "expand an every-N-months cadence" in {
      val gym =
        subscription(List(term("2026-01-15")), cadence = SubscriptionCadence.EveryNMonths, intervalMonths = Some(3))
      val jan = bankRow("2026-01-15")
      val apr = bankRow("2026-04-16")
      val result = reconcile(List(gym), List(jan, apr), today = "2026-08-01")
      statuses(result, gym) must be(
        List(
          date("2026-01-15") -> Matched(charge(jan), late = false),
          date("2026-04-15") -> Matched(charge(apr), late = true),
          date("2026-07-15") -> Missing,
          date("2026-10-15") -> Upcoming
        )
      )
    }

    "expand a yearly cadence" in {
      val gym = subscription(List(term("2024-03-01")), cadence = SubscriptionCadence.Yearly)
      val first = expense("2024-03-01")
      val third = expense("2026-02-27")
      val result = reconcile(List(gym), List(first, third), today = "2026-06-01")
      statuses(result, gym) must be(
        List(
          date("2024-03-01") -> Matched(charge(first), late = false),
          date("2025-03-01") -> Missing,
          date("2026-03-01") -> Matched(charge(third), late = false),
          date("2027-03-01") -> Upcoming
        )
      )
    }

    "credit a manual payment to the cycle whose bucket contains it, one payment per cycle" in {
      val gym = subscription(List(term("2026-02-10")))
      val paid = manual(gym, "2026-02-11")
      val second = manual(gym, "2026-02-20")
      val result = reconcile(List(gym), List.empty, today = "2026-03-20", manualPayments = List(second, paid))
      statuses(result, gym) must be(
        List(
          date("2026-02-10") -> Matched(manualCharge(paid), late = true),
          date("2026-03-10") -> Missing,
          date("2026-04-10") -> Upcoming
        )
      )
      result(gym.id).manualPayments must be(List(paid.payment, second.payment))
    }

    "keep a cycle paid by a manual payment out of charge matching" in {
      val gym = subscription(List(term("2026-02-10")))
      val paid = manual(gym, "2026-02-10")
      val row = bankRow("2026-02-10")
      val result = reconcile(List(gym), List(row), today = "2026-02-20", manualPayments = List(paid))
      statuses(result, gym).head must be(date("2026-02-10") -> Matched(manualCharge(paid), late = false))
    }

    "match a cycle with no amount and no merchant only by a manual payment" in {
      val gym = subscription(List(term("2026-02-10", amount = None)))
      val paid = manual(gym, "2026-03-09", amount = "12.34")
      val result = reconcile(
        List(gym),
        List(bankRow("2026-02-10"), bankRow("2026-03-10")),
        today = "2026-03-20",
        manualPayments = List(paid)
      )
      statuses(result, gym) must be(
        List(
          date("2026-02-10") -> Missing,
          date("2026-03-10") -> Matched(manualCharge(paid), late = false),
          date("2026-04-10") -> Upcoming
        )
      )
    }

    "leave a cycle missing when it is paid by a charge outside the term's amount range" in {
      val gym = subscription(List(term("2026-02-10")))
      val result = reconcile(List(gym), List(bankRow("2026-02-10", amount = "10.01")), today = "2026-02-15")
      statuses(result, gym).head must be(date("2026-02-10") -> Upcoming)
      val closed = reconcile(List(gym), List(bankRow("2026-02-10", amount = "9.99")), today = "2026-02-16")
      statuses(closed, gym).head must be(date("2026-02-10") -> Missing)
    }

    "match charges at both bounds and inside a variable amount range" in {
      val gym = subscription(List(rangeTerm("2026-01-10", min = Some("8.00"), max = Some("12.00"))))
      val atMin = bankRow("2026-01-10", amount = "8.00")
      val inside = bankRow("2026-02-10", amount = "9.50")
      val atMax = bankRow("2026-03-10", amount = "12.00")
      val result = reconcile(List(gym), List(atMin, inside, atMax), today = "2026-03-20")
      statuses(result, gym).take(3) must be(
        List(
          date("2026-01-10") -> Matched(charge(atMin), late = false),
          date("2026-02-10") -> Matched(charge(inside), late = false),
          date("2026-03-10") -> Matched(charge(atMax), late = false)
        )
      )
    }

    "treat a term with only one amount bound as an unknown amount" in {
      val gym = subscription(List(rangeTerm("2026-02-10", min = Some("10.00"), max = None)))
      statuses(reconcile(List(gym), List(bankRow("2026-02-10")), today = "2026-02-20"), gym).head must be(
        date("2026-02-10") -> Missing
      )
    }

    "list but not credit a manual payment made before the first cycle's bucket" in {
      val gym = subscription(List(term("2026-02-10")))
      val early = manual(gym, "2026-01-20")
      val result = reconcile(List(gym), List.empty, today = "2026-02-20", manualPayments = List(early))
      statuses(result, gym).head must be(date("2026-02-10") -> Missing)
      result(gym.id).manualPayments must be(List(early.payment))
    }

    "not credit a manual payment made during a cancel/resume gap to the cycle before it" in {
      val gym = subscription(List(term("2026-01-10", to = Some("2026-02-01")), term("2026-05-20")))
      val inGap = manual(gym, "2026-03-15")
      val result = reconcile(List(gym), List.empty, today = "2026-06-01", manualPayments = List(inGap))
      statuses(result, gym).head must be(date("2026-01-10") -> Missing)
      result(gym.id).manualPayments must be(List(inGap.payment))
    }

    "credit a late manual payment made just after the subscription ended" in {
      val gym = subscription(List(term("2026-01-10", to = Some("2026-01-12"))))
      val late = manual(gym, "2026-01-14")
      val result = reconcile(List(gym), List.empty, today = "2026-03-01", manualPayments = List(late))
      statuses(result, gym) must be(List(date("2026-01-10") -> Matched(manualCharge(late), late = true)))
    }

    "fail for a negative tolerance" in {
      val gym = subscription(List(term("2026-02-10")))
      SubscriptionReconciler.reconcile(List(gym), List.empty, List.empty, date("2026-03-01"), -1) must be(
        Left(SubscriptionReconcileError.NegativeTolerance(-1))
      )
    }

    "fail for a manual payment of an unknown subscription" in {
      val gym = subscription(List(term("2026-02-10")))
      val other = subscription(List(term("2026-02-10")))
      val payment = manual(other, "2026-02-10")
      SubscriptionReconciler.reconcile(List(gym), List(payment), List.empty, date("2026-03-01"), 5) must be(
        Left(SubscriptionReconcileError.UnknownSubscription(payment.payment.id, other.id))
      )
    }

    "fail when a subscription's schedule cannot be computed" in {
      val gym = subscription(List(term("2026-02-10")), cadence = SubscriptionCadence.EveryNDays)
      SubscriptionReconciler.reconcile(List(gym), List.empty, List.empty, date("2026-03-01"), 5) must be(
        Left(SubscriptionReconcileError.InvalidSchedule(gym.id, SubscriptionScheduleError.MissingInterval))
      )
    }
  }
}
