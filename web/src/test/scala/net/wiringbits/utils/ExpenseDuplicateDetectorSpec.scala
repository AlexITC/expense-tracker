package net.wiringbits.utils

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.utils.ExpenseDuplicateDetector.{Candidate, Tier}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class ExpenseDuplicateDetectorSpec extends AnyWordSpec {

  private val baseDate = LocalDate.of(2024, 3, 15)
  private val window = ReconciliationMatcher.MaxDateDifferenceDays

  private def expense(
      amount: BigDecimal = BigDecimal("10.00"),
      date: LocalDate = baseDate,
      merchantId: Option[UUID] = None,
      hasRow: Boolean = false,
      hasReceipt: Boolean = false
  ): GetExpenses.Response.Expense =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = amount,
      date = date,
      time = None,
      merchantId = merchantId,
      merchantName = None,
      notes = None,
      splits = Nil,
      createdAt = Instant.now(),
      receiptIds = if (hasReceipt) List(UUID.randomUUID()) else Nil,
      rowIds = if (hasRow) List(UUID.randomUUID()) else Nil
    )

  private def survivor(
      amount: BigDecimal = BigDecimal("10.00"),
      date: LocalDate = baseDate,
      merchantId: Option[UUID] = None
  ) = expense(amount, date, merchantId, hasRow = true)

  private def loser(
      amount: BigDecimal = BigDecimal("10.00"),
      date: LocalDate = baseDate,
      merchantId: Option[UUID] = None
  ) = expense(amount, date, merchantId, hasReceipt = true)

  private def orphan(
      amount: BigDecimal = BigDecimal("10.00"),
      date: LocalDate = baseDate,
      merchantId: Option[UUID] = None
  ) = expense(amount, date, merchantId)

  "detect" should {
    "mark a pair with the same merchant as Strong" in {
      val merchantId = Some(UUID.randomUUID())
      val s = survivor(merchantId = merchantId)
      val l = loser(merchantId = merchantId)
      ExpenseDuplicateDetector.detect(List(s, l)) must be(
        List(Candidate(s, l, l.receiptIds.headOption, Tier.Strong, ambiguous = false))
      )
    }

    "mark a pair with different merchants as Weak" in {
      val s = survivor(merchantId = Some(UUID.randomUUID()))
      val l = loser(merchantId = Some(UUID.randomUUID()))
      ExpenseDuplicateDetector.detect(List(s, l)) must be(
        List(Candidate(s, l, l.receiptIds.headOption, Tier.Weak, ambiguous = false))
      )
    }

    "mark a pair where only one side has a merchant as Weak" in {
      val s = survivor(merchantId = Some(UUID.randomUUID()))
      val l = loser()
      ExpenseDuplicateDetector.detect(List(s, l)) must be(
        List(Candidate(s, l, l.receiptIds.headOption, Tier.Weak, ambiguous = false))
      )
    }

    "mark a pair where both merchants are None as Weak" in {
      val s = survivor()
      val l = loser()
      ExpenseDuplicateDetector.detect(List(s, l)) must be(
        List(Candidate(s, l, l.receiptIds.headOption, Tier.Weak, ambiguous = false))
      )
    }

    "require the exact amount" in {
      val s = survivor(amount = BigDecimal("10.00"))
      val l = loser(amount = BigDecimal("10.01"))
      ExpenseDuplicateDetector.detect(List(s, l)) must be(Nil)
    }

    "match a date difference of 0 days" in {
      val s = survivor()
      val l = loser()
      ExpenseDuplicateDetector.detect(List(s, l)).map(_.loser) must be(List(l))
    }

    "match a date difference of exactly MaxDateDifferenceDays in both directions" in {
      val s = survivor()
      val before = loser(date = baseDate.minusDays(window))
      val after = loser(date = baseDate.plusDays(window))
      ExpenseDuplicateDetector.detect(List(s, before, after)).map(_.loser).toSet must be(Set(before, after))
    }

    "exclude a date difference of MaxDateDifferenceDays + 1 in both directions" in {
      val s = survivor()
      val before = loser(date = baseDate.minusDays(window + 1))
      val after = loser(date = baseDate.plusDays(window + 1))
      ExpenseDuplicateDetector.detect(List(s, before, after)) must be(Nil)
    }

    "exclude pairs where both expenses have a row" in {
      val a = expense(hasRow = true)
      val b = expense(hasRow = true, hasReceipt = true)
      ExpenseDuplicateDetector.detect(List(a, b)) must be(Nil)
    }

    "exclude pairs where neither expense has a row" in {
      val a = expense()
      val b = expense(hasReceipt = true)
      ExpenseDuplicateDetector.detect(List(a, b)) must be(Nil)
    }

    "assign the survivor to the expense with a row regardless of input order" in {
      val s = survivor()
      val l = loser()
      val expected = List(Candidate(s, l, l.receiptIds.headOption, Tier.Weak, ambiguous = false))
      ExpenseDuplicateDetector.detect(List(s, l)) must be(expected)
      ExpenseDuplicateDetector.detect(List(l, s)) must be(expected)
    }

    "carry the loser's first receipt id" in {
      val s = survivor()
      val receiptId = UUID.randomUUID()
      val l = loser().copy(receiptIds = List(receiptId, UUID.randomUUID()))
      ExpenseDuplicateDetector.detect(List(s, l)).map(_.receiptId) must be(List(Some(receiptId)))
    }

    "exclude a survivor that already has a receipt from receipt pairs" in {
      val s = expense(hasRow = true, hasReceipt = true)
      val l = loser()
      ExpenseDuplicateDetector.detect(List(s, l)) must be(Nil)
    }

    "pair an orphan with the same merchant as Strong, without a receipt id" in {
      val merchantId = Some(UUID.randomUUID())
      val s = survivor(merchantId = merchantId)
      val o = orphan(merchantId = merchantId)
      ExpenseDuplicateDetector.detect(List(s, o)) must be(List(Candidate(s, o, None, Tier.Strong, ambiguous = false)))
    }

    "pair an orphan with a different merchant as Weak" in {
      val s = survivor(merchantId = Some(UUID.randomUUID()))
      val o = orphan(merchantId = Some(UUID.randomUUID()))
      ExpenseDuplicateDetector.detect(List(s, o)) must be(List(Candidate(s, o, None, Tier.Weak, ambiguous = false)))
    }

    "require the exact amount for orphans" in {
      val s = survivor(amount = BigDecimal("10.00"))
      val o = orphan(amount = BigDecimal("10.01"))
      ExpenseDuplicateDetector.detect(List(s, o)) must be(Nil)
    }

    "match orphans within MaxDateDifferenceDays and exclude them outside it" in {
      val s = survivor()
      val inside = orphan(date = baseDate.minusDays(window))
      val before = orphan(date = baseDate.minusDays(window + 1))
      val after = orphan(date = baseDate.plusDays(window + 1))
      ExpenseDuplicateDetector.detect(List(s, inside, before, after)).map(_.loser) must be(List(inside))
    }

    "pair an orphan with a survivor that already has a receipt" in {
      val s = expense(hasRow = true, hasReceipt = true)
      val o = orphan()
      ExpenseDuplicateDetector.detect(List(s, o)) must be(List(Candidate(s, o, None, Tier.Weak, ambiguous = false)))
    }

    "not pair two expenses without a row" in {
      val o = orphan()
      val l = loser()
      ExpenseDuplicateDetector.detect(List(o, l)) must be(Nil)
    }

    "flag a survivor matching both an orphan and a receipt loser as ambiguous" in {
      val s = survivor()
      val o = orphan()
      val l = loser()
      ExpenseDuplicateDetector.detect(List(s, o, l)).toSet must be(
        Set(
          Candidate(s, l, l.receiptIds.headOption, Tier.Weak, ambiguous = true),
          Candidate(s, o, None, Tier.Weak, ambiguous = true)
        )
      )
    }

    "order by survivor date descending, then survivor id, then loser id" in {
      val older = survivor(date = baseDate.minusDays(10))
      val newer = survivor()
      val olderLoser = loser(date = older.date)
      val newerLosers = List(loser(), loser())

      val result = ExpenseDuplicateDetector.detect(olderLoser :: older :: newerLosers ::: List(newer))
      result.map(c => (c.survivor, c.loser)) must be(
        newerLosers.sortBy(_.id.toString).map(newer -> _) :+ (older -> olderLoser)
      )
    }

    "return a candidate for each loser and flag them ambiguous when one survivor matches several losers" in {
      val merchantId = Some(UUID.randomUUID())
      val s = survivor(merchantId = merchantId)
      val strong = loser(merchantId = merchantId)
      val weak = loser(date = baseDate.plusDays(1))

      ExpenseDuplicateDetector.detect(List(s, strong, weak)).toSet must be(
        Set(
          Candidate(s, strong, strong.receiptIds.headOption, Tier.Strong, ambiguous = true),
          Candidate(s, weak, weak.receiptIds.headOption, Tier.Weak, ambiguous = true)
        )
      )
    }

    "return a candidate for each survivor and flag them ambiguous when one loser matches several survivors" in {
      val s1 = survivor()
      val s2 = survivor(date = baseDate.plusDays(2))
      val l = loser()

      ExpenseDuplicateDetector.detect(List(s1, s2, l)) must be(
        List(
          Candidate(s2, l, l.receiptIds.headOption, Tier.Weak, ambiguous = true),
          Candidate(s1, l, l.receiptIds.headOption, Tier.Weak, ambiguous = true)
        )
      )
    }

    "flag every pair ambiguous when two survivors and two losers all match" in {
      val merchantId = Some(UUID.randomUUID())
      val a = survivor(merchantId = merchantId)
      val b = survivor(date = baseDate.plusDays(1), merchantId = merchantId)
      val l1 = loser(merchantId = merchantId)
      val l2 = loser(date = baseDate.plusDays(1), merchantId = merchantId)

      val result = ExpenseDuplicateDetector.detect(List(a, b, l1, l2))
      result.map(c => (c.survivor, c.loser)).toSet must be(Set(a -> l1, a -> l2, b -> l1, b -> l2))
      result.forall(c => c.tier == Tier.Strong && c.ambiguous) must be(true)
    }

    "not flag disjoint pairs as ambiguous" in {
      val s1 = survivor()
      val l1 = loser()
      val s2 = survivor(amount = BigDecimal("20.00"))
      val l2 = loser(amount = BigDecimal("20.00"))

      ExpenseDuplicateDetector.detect(List(s1, l1, s2, l2)).map(_.ambiguous) must be(List(false, false))
    }
  }
}
