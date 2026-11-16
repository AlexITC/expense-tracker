package net.wiringbits.api.utils

import net.wiringbits.api.utils.DateAmountMatcher.{Candidate, Slot, SlotResult}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.UUID

class DateAmountMatcherSpec extends AnyWordSpec with Matchers {

  private val tolerance = 5
  private val gym = UUID.randomUUID()
  private val streaming = UUID.randomUUID()
  private val tenToTwelve = Some((BigDecimal("10.00"), BigDecimal("12.00")))

  private def date(s: String): LocalDate = LocalDate.parse(s)

  private def slot(
      id: String,
      targetDate: String,
      amountRange: Option[(BigDecimal, BigDecimal)] = tenToTwelve,
      merchantHint: Option[UUID] = None
  ): Slot[String] = Slot(id, date(targetDate), amountRange, merchantHint)

  private def candidate(
      id: String,
      on: String,
      amount: String = "10.00",
      merchantId: Option[UUID] = None
  ): Candidate[String] = Candidate(id, date(on), BigDecimal(amount), merchantId)

  // Slot ids look like "<group>-<n>", the group is the prefix.
  private def groupOf(slot: Slot[String]): String = slot.id.takeWhile(_ != '-')

  private def assign(
      slots: List[Slot[String]],
      candidates: List[Candidate[String]]
  ): List[SlotResult[String, String]] =
    DateAmountMatcher.assign(slots, candidates, tolerance, groupOf)

  private def primaries(results: List[SlotResult[String, String]]): Map[String, Option[String]] =
    results.map(r => r.slot.id -> r.primary.map(_.id)).toMap

  private def extras(results: List[SlotResult[String, String]]): Map[String, List[String]] =
    results.map(r => r.slot.id -> r.extraAttempts.map(_.id)).toMap

  "isEligible" should {
    "accept the exact window edges on both sides" in {
      val s = slot("a-1", "2026-03-10")
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-05"), tolerance) must be(true)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-15"), tolerance) must be(true)
    }

    "reject one day past the window on both sides" in {
      val s = slot("a-1", "2026-03-10")
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-04"), tolerance) must be(false)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-16"), tolerance) must be(false)
    }

    "accept the inclusive amount range edges and reject amounts outside" in {
      val s = slot("a-1", "2026-03-10")
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", "10.00"), tolerance) must be(true)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", "12.00"), tolerance) must be(true)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", "9.99"), tolerance) must be(false)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", "12.01"), tolerance) must be(false)
    }

    "reject a merchant mismatch" in {
      val s = slot("a-1", "2026-03-10", merchantHint = Some(gym))
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", merchantId = Some(streaming)), tolerance) must be(
        false
      )
    }

    "accept a candidate without a merchant" in {
      val s = slot("a-1", "2026-03-10", merchantHint = Some(gym))
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10"), tolerance) must be(true)
    }

    "accept any amount from the hinted merchant when there is no range" in {
      val s = slot("a-1", "2026-03-10", amountRange = None, merchantHint = Some(gym))
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", "99.00", Some(gym)), tolerance) must be(true)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", "99.00"), tolerance) must be(false)
    }

    "accept nothing when the slot has neither a range nor a hint" in {
      val s = slot("a-1", "2026-03-10", amountRange = None)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10"), tolerance) must be(false)
      DateAmountMatcher.isEligible(s, candidate("c", "2026-03-10", merchantId = Some(gym)), tolerance) must be(false)
    }
  }

  "assign" should {
    "return nothing for empty inputs" in {
      assign(List.empty, List.empty) must be(List.empty)
      assign(List.empty, List(candidate("c", "2026-03-10"))) must be(List.empty)
      assign(List(slot("a-1", "2026-03-10")), List.empty) must be(
        List(SlotResult(slot("a-1", "2026-03-10"), None, List.empty))
      )
    }

    "prefer the hinted merchant over a closer date on a same-amount collision" in {
      val s = slot("a-1", "2026-03-10", merchantHint = Some(gym))
      val closer = candidate("closer", "2026-03-10")
      val hinted = candidate("hinted", "2026-03-13", merchantId = Some(gym))
      val result = assign(List(s), List(closer, hinted))
      primaries(result) must be(Map("a-1" -> Some("hinted")))
    }

    "pick the closest date when no merchant is involved" in {
      val s = slot("a-1", "2026-03-10")
      val result = assign(List(s), List(candidate("far", "2026-03-14"), candidate("near", "2026-03-09")))
      primaries(result) must be(Map("a-1" -> Some("near")))
    }

    "never use a candidate for two slots" in {
      val slots = List(slot("a-1", "2026-03-10"), slot("b-1", "2026-03-11"))
      val result = assign(slots, List(candidate("only", "2026-03-11")))
      primaries(result) must be(Map("a-1" -> None, "b-1" -> Some("only")))
      extras(result) must be(Map("a-1" -> List.empty, "b-1" -> List.empty))
    }

    "add unused eligible candidates as extra attempts of the nearest matched slot in the group" in {
      val slots = List(slot("a-1", "2026-03-01"), slot("a-2", "2026-03-08"))
      val candidates = List(
        candidate("paid-1", "2026-03-01"),
        candidate("paid-2", "2026-03-08"),
        candidate("retry-late", "2026-03-06"),
        candidate("retry-early", "2026-03-03"),
        candidate("retry-same-day", "2026-03-03", "11.00")
      )
      val result = assign(slots, candidates)
      primaries(result) must be(Map("a-1" -> Some("paid-1"), "a-2" -> Some("paid-2")))
      extras(result) must be(Map("a-1" -> List("retry-early", "retry-same-day"), "a-2" -> List("retry-late")))
    }

    "not credit a retry that is eligible for slots of different groups" in {
      val slots = List(slot("a-1", "2026-03-01"), slot("b-1", "2026-03-04"))
      val candidates = List(
        candidate("paid-a", "2026-03-01"),
        candidate("paid-b", "2026-03-04"),
        candidate("ambiguous", "2026-03-03")
      )
      val result = assign(slots, candidates)
      primaries(result) must be(Map("a-1" -> Some("paid-a"), "b-1" -> Some("paid-b")))
      extras(result) must be(Map("a-1" -> List.empty, "b-1" -> List.empty))
    }

    "credit a retry to its own group when it is only eligible there" in {
      val slots = List(slot("a-1", "2026-03-01"), slot("b-1", "2026-03-04", tenToTwelve, Some(streaming)))
      val candidates = List(
        candidate("paid-a", "2026-03-01"),
        candidate("paid-b", "2026-03-04", merchantId = Some(streaming)),
        candidate("retry-a", "2026-03-03", merchantId = Some(gym))
      )
      val result = assign(slots, candidates)
      extras(result) must be(Map("a-1" -> List("retry-a"), "b-1" -> List.empty))
    }

    "give a candidate at the same distance to the earlier slot even when it has the larger id" in {
      val result = assign(
        List(slot("a-1", "2026-03-12"), slot("b-1", "2026-03-08")),
        List(candidate("mid", "2026-03-10"))
      )
      primaries(result) must be(Map("a-1" -> None, "b-1" -> Some("mid")))
    }

    "give a candidate to the smaller slot id when slot dates are equal" in {
      val result = assign(
        List(slot("b-1", "2026-03-10"), slot("a-1", "2026-03-10")),
        List(candidate("only", "2026-03-10"))
      )
      primaries(result) must be(Map("a-1" -> Some("only"), "b-1" -> None))
    }

    "pick the smaller candidate id as primary on an equal date and keep the other as a retry" in {
      val result = assign(
        List(slot("a-1", "2026-03-10")),
        List(candidate("y", "2026-03-10"), candidate("x", "2026-03-10"))
      )
      primaries(result) must be(Map("a-1" -> Some("x")))
      extras(result) must be(Map("a-1" -> List("y")))
    }

    "give a same-amount candidate to the slot with the matching merchant over a closer slot" in {
      val slots = List(slot("a-1", "2026-03-10", merchantHint = Some(gym)), slot("b-1", "2026-03-12"))
      val result = assign(slots, List(candidate("charge", "2026-03-12", merchantId = Some(gym))))
      primaries(result) must be(Map("a-1" -> Some("charge"), "b-1" -> None))
    }

    "keep the input slot order in the results" in {
      val slots = List(slot("b-1", "2026-03-20"), slot("a-1", "2026-03-01"))
      assign(slots, List.empty).map(_.slot.id) must be(List("b-1", "a-1"))
    }
  }
}
