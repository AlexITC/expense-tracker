package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.SubscriptionTerm
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import java.util.UUID

class SubscriptionTermsSpec extends AnyWordSpec with Matchers {

  private def term(
      from: LocalDate,
      to: Option[LocalDate],
      amountMin: Option[BigDecimal] = Some(BigDecimal("10.00")),
      amountMax: Option[BigDecimal] = Some(BigDecimal("10.00"))
  ): SubscriptionTerm =
    SubscriptionTerm(
      id = UUID.randomUUID(),
      effectiveFrom = from,
      effectiveTo = to,
      amountMin = amountMin,
      amountMax = amountMax
    )

  private def day(month: Int, dayOfMonth: Int): LocalDate = LocalDate.of(2025, month, dayOfMonth)

  // Consecutive closed terms starting at 2000-01-01, one day each.
  private def closedTerms(count: Int): List[SubscriptionTerm] = {
    val start = LocalDate.of(2000, 1, 1)
    List.tabulate(count)(i => term(start.plusDays(i.toLong), Some(start.plusDays(i.toLong + 1))))
  }

  "validateTerms" should {
    "accept an empty history" in {
      SubscriptionTerms.validateTerms(List.empty) mustBe Right(())
    }

    "accept adjacent terms whose last one is open" in {
      SubscriptionTerms.validateTerms(
        List(term(day(1, 1), Some(day(3, 1))), term(day(3, 1), Some(day(6, 1))), term(day(6, 1), None))
      ) mustBe Right(())
    }

    "accept terms in any input order" in {
      SubscriptionTerms.validateTerms(List(term(day(3, 1), None), term(day(1, 1), Some(day(2, 1))))) mustBe Right(())
    }

    "accept a gap between terms" in {
      SubscriptionTerms.validateTerms(List(term(day(1, 1), Some(day(2, 1))), term(day(5, 1), None))) mustBe Right(())
    }

    "reject overlapping terms" in {
      SubscriptionTerms.validateTerms(List(term(day(1, 1), Some(day(3, 2))), term(day(3, 1), None))) mustBe
        Left("The term from 2025-01-01 overlaps the term from 2025-03-01")
    }

    "reject an open term that is not the last one" in {
      SubscriptionTerms.validateTerms(List(term(day(1, 1), None), term(day(3, 1), None))) mustBe
        Left("Only the last term may be open, but the term from 2025-01-01 is open")
    }

    "reject a term that ends on or before it starts" in {
      SubscriptionTerms.validateTerms(List(term(day(3, 1), Some(day(3, 1))))) mustBe
        Left("A term ending on 2025-03-01 must end after it starts on 2025-03-01")
      SubscriptionTerms.validateTerms(List(term(day(3, 1), Some(day(2, 1))))) mustBe
        Left("A term ending on 2025-02-01 must end after it starts on 2025-03-01")
    }

    "reject a term with an invalid amount range" in {
      SubscriptionTerms.validateTerms(
        List(term(day(1, 1), None, amountMin = Some(BigDecimal("5")), amountMax = None))
      ) mustBe Left("Minimum and maximum amounts must be both set or both empty")
    }

    "accept exactly the maximum number of terms" in {
      SubscriptionTerms.validateTerms(closedTerms(SubscriptionValidator.MaxTermsPerSubscription)) mustBe Right(())
    }

    "reject more than the maximum number of terms" in {
      SubscriptionTerms.validateTerms(closedTerms(SubscriptionValidator.MaxTermsPerSubscription + 1)) mustBe
        Left("Maximum number of terms (200) reached")
    }
  }

  "priceChange" should {
    "close the open term at the new date and open a term with the new amounts" in {
      val previous = term(day(1, 1), Some(day(2, 1)))
      val current = term(day(2, 1), None)
      val newId = UUID.randomUUID()

      val plan = SubscriptionTerms
        .priceChange(
          List(previous, current),
          newTermId = newId,
          effectiveFrom = day(5, 1),
          amountMin = Some(BigDecimal("12.00")),
          amountMax = Some(BigDecimal("15.00"))
        )
        .toOption
        .get

      val opened = SubscriptionTerm(
        id = newId,
        effectiveFrom = day(5, 1),
        effectiveTo = None,
        amountMin = Some(BigDecimal("12.00")),
        amountMax = Some(BigDecimal("15.00"))
      )
      plan.close mustBe Some(SubscriptionTerms.Close(termId = current.id, effectiveTo = day(5, 1)))
      plan.open mustBe Some(opened)
      plan.terms mustBe List(previous, current.copy(effectiveTo = Some(day(5, 1))), opened)
    }

    "reject a cancelled subscription" in {
      SubscriptionTerms.priceChange(
        List(term(day(1, 1), Some(day(2, 1)))),
        newTermId = UUID.randomUUID(),
        effectiveFrom = day(3, 1),
        amountMin = None,
        amountMax = None
      ) mustBe Left("Subscription is cancelled, resume it instead")
    }

    "reject a date on or before the open term's start" in {
      List(day(2, 1), day(1, 15)).foreach { from =>
        SubscriptionTerms.priceChange(
          List(term(day(2, 1), None)),
          newTermId = UUID.randomUUID(),
          effectiveFrom = from,
          amountMin = None,
          amountMax = None
        ) mustBe Left("The new price must start after the current term starts on 2025-02-01")
      }
    }

    "reject an invalid amount range" in {
      SubscriptionTerms.priceChange(
        List(term(day(2, 1), None)),
        newTermId = UUID.randomUUID(),
        effectiveFrom = day(3, 1),
        amountMin = Some(BigDecimal("20")),
        amountMax = Some(BigDecimal("10"))
      ) mustBe Left("Minimum amount must not be greater than the maximum amount")
    }

    "reject a change once the maximum number of terms is reached" in {
      val history = closedTerms(SubscriptionValidator.MaxTermsPerSubscription - 1)
      val open = term(history.last.effectiveTo.get, None)

      SubscriptionTerms.priceChange(
        history :+ open,
        newTermId = UUID.randomUUID(),
        effectiveFrom = open.effectiveFrom.plusDays(1),
        amountMin = None,
        amountMax = None
      ) mustBe Left("Maximum number of terms (200) reached")
    }
  }

  "cancel" should {
    "close the open term at the cancellation date" in {
      val current = term(day(2, 1), None)

      val plan = SubscriptionTerms.cancel(List(current), effectiveTo = day(4, 1)).toOption.get

      plan.close mustBe Some(SubscriptionTerms.Close(termId = current.id, effectiveTo = day(4, 1)))
      plan.open mustBe None
      plan.terms mustBe List(current.copy(effectiveTo = Some(day(4, 1))))
    }

    "reject an already cancelled subscription" in {
      SubscriptionTerms.cancel(List(term(day(1, 1), Some(day(2, 1)))), effectiveTo = day(3, 1)) mustBe
        Left("Subscription is already cancelled")
    }

    "reject a date on or before the open term's start" in {
      List(day(2, 1), day(1, 15)).foreach { to =>
        SubscriptionTerms.cancel(List(term(day(2, 1), None)), effectiveTo = to) mustBe
          Left("The cancellation date must be after the current term starts on 2025-02-01")
      }
    }
  }

  "resume" should {
    "open a term on the last term's end date" in {
      val previous = term(day(1, 1), Some(day(3, 1)))
      val newId = UUID.randomUUID()

      val plan = SubscriptionTerms
        .resume(List(previous), newTermId = newId, effectiveFrom = day(3, 1), amountMin = None, amountMax = None)
        .toOption
        .get

      val opened =
        SubscriptionTerm(id = newId, effectiveFrom = day(3, 1), effectiveTo = None, amountMin = None, amountMax = None)
      plan.close mustBe None
      plan.open mustBe Some(opened)
      plan.terms mustBe List(previous, opened)
    }

    "open a term after a gap" in {
      SubscriptionTerms
        .resume(
          List(term(day(1, 1), Some(day(3, 1)))),
          newTermId = UUID.randomUUID(),
          effectiveFrom = day(7, 1),
          amountMin = None,
          amountMax = None
        )
        .map(_.terms.size) mustBe Right(2)
    }

    "reject a subscription that is not cancelled" in {
      SubscriptionTerms.resume(
        List(term(day(1, 1), None)),
        newTermId = UUID.randomUUID(),
        effectiveFrom = day(3, 1),
        amountMin = None,
        amountMax = None
      ) mustBe Left("Subscription is not cancelled")
    }

    "reject a subscription without terms" in {
      SubscriptionTerms.resume(
        List.empty,
        newTermId = UUID.randomUUID(),
        effectiveFrom = day(3, 1),
        amountMin = None,
        amountMax = None
      ) mustBe Left("Subscription has no terms")
    }

    "reject a date before the last term's end" in {
      SubscriptionTerms.resume(
        List(term(day(1, 1), Some(day(3, 1)))),
        newTermId = UUID.randomUUID(),
        effectiveFrom = day(2, 28),
        amountMin = None,
        amountMax = None
      ) mustBe Left("The resume date must be on or after 2025-03-01")
    }

    "reject an invalid amount range" in {
      SubscriptionTerms.resume(
        List(term(day(1, 1), Some(day(3, 1)))),
        newTermId = UUID.randomUUID(),
        effectiveFrom = day(3, 1),
        amountMin = None,
        amountMax = Some(BigDecimal("5"))
      ) mustBe Left("Minimum and maximum amounts must be both set or both empty")
    }

    "reject a resume once the maximum number of terms is reached" in {
      val history = closedTerms(SubscriptionValidator.MaxTermsPerSubscription)

      SubscriptionTerms.resume(
        history,
        newTermId = UUID.randomUUID(),
        effectiveFrom = history.last.effectiveTo.get,
        amountMin = None,
        amountMax = None
      ) mustBe Left("Maximum number of terms (200) reached")
    }
  }
}
