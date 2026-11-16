package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.SubscriptionTerm

import java.time.LocalDate
import java.util.UUID

/** Pure rules for a subscription's term history.
  *
  * Terms cover half-open ranges [effectiveFrom, effectiveTo): adjacent terms (`prev.effectiveTo == next.effectiveFrom`)
  * are fine, overlaps are not, and only the last term may be open. The planners return the writes that move the history
  * from one valid state to the next, so the server applies exactly what was checked here.
  */
object SubscriptionTerms {

  /** Ends the term `termId` at the exclusive date `effectiveTo`. */
  case class Close(termId: UUID, effectiveTo: LocalDate)

  /** The writes to apply plus the resulting history, ordered by effectiveFrom. */
  case class Plan(close: Option[Close], open: Option[SubscriptionTerm], terms: List[SubscriptionTerm])

  def validateTerms(terms: List[SubscriptionTerm]): Either[String, Unit] = {
    val sorted = sortTerms(terms)
    val pairs = sorted.zip(sorted.drop(1))
    for {
      _ <-
        if (terms.size > SubscriptionValidator.MaxTermsPerSubscription)
          Left(s"Maximum number of terms (${SubscriptionValidator.MaxTermsPerSubscription}) reached")
        else Right(())
      _ <- firstError(sorted) { term =>
        term.effectiveTo match {
          case Some(to) if !to.isAfter(term.effectiveFrom) =>
            Left(s"A term ending on $to must end after it starts on ${term.effectiveFrom}")
          case _ => SubscriptionValidator.validateAmountRange(amountMin = term.amountMin, amountMax = term.amountMax)
        }
      }
      _ <- firstError(pairs) { case (prev, next) =>
        prev.effectiveTo match {
          case None => Left(s"Only the last term may be open, but the term from ${prev.effectiveFrom} is open")
          case Some(to) if to.isAfter(next.effectiveFrom) =>
            Left(s"The term from ${prev.effectiveFrom} overlaps the term from ${next.effectiveFrom}")
          case Some(_) => Right(())
        }
      }
    } yield ()
  }

  /** Closes the open term at `effectiveFrom` and opens a new term with the new amounts from that date. */
  def priceChange(
      terms: List[SubscriptionTerm],
      newTermId: UUID,
      effectiveFrom: LocalDate,
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal]
  ): Either[String, Plan] =
    for {
      current <- openTerm(terms).toRight("Subscription is cancelled, resume it instead")
      _ <-
        if (effectiveFrom.isAfter(current.effectiveFrom)) Right(())
        else Left(s"The new price must start after the current term starts on ${current.effectiveFrom}")
      close = Close(termId = current.id, effectiveTo = effectiveFrom)
      open = SubscriptionTerm(
        id = newTermId,
        effectiveFrom = effectiveFrom,
        effectiveTo = None,
        amountMin = amountMin,
        amountMax = amountMax
      )
      plan <- planned(terms, Some(close), Some(open))
    } yield plan

  /** Ends the open term at the exclusive date `effectiveTo`. */
  def cancel(terms: List[SubscriptionTerm], effectiveTo: LocalDate): Either[String, Plan] =
    for {
      current <- openTerm(terms).toRight("Subscription is already cancelled")
      _ <-
        if (effectiveTo.isAfter(current.effectiveFrom)) Right(())
        else Left(s"The cancellation date must be after the current term starts on ${current.effectiveFrom}")
      plan <- planned(terms, Some(Close(termId = current.id, effectiveTo = effectiveTo)), None)
    } yield plan

  /** Opens a new term on a cancelled subscription, starting no earlier than the last term ended. */
  def resume(
      terms: List[SubscriptionTerm],
      newTermId: UUID,
      effectiveFrom: LocalDate,
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal]
  ): Either[String, Plan] =
    for {
      _ <- openTerm(terms).fold[Either[String, Unit]](Right(()))(_ => Left("Subscription is not cancelled"))
      last <- sortTerms(terms).lastOption.toRight("Subscription has no terms")
      lastEnd <- last.effectiveTo.toRight("Subscription is not cancelled")
      _ <-
        if (effectiveFrom.isBefore(lastEnd)) Left(s"The resume date must be on or after $lastEnd")
        else Right(())
      open = SubscriptionTerm(
        id = newTermId,
        effectiveFrom = effectiveFrom,
        effectiveTo = None,
        amountMin = amountMin,
        amountMax = amountMax
      )
      plan <- planned(terms, None, Some(open))
    } yield plan

  private def planned(
      terms: List[SubscriptionTerm],
      close: Option[Close],
      open: Option[SubscriptionTerm]
  ): Either[String, Plan] = {
    val closed = terms.map { term =>
      close match {
        case Some(c) if c.termId == term.id => term.copy(effectiveTo = Some(c.effectiveTo))
        case _ => term
      }
    }
    val result = sortTerms(closed ++ open.toList)
    validateTerms(result).map(_ => Plan(close = close, open = open, terms = result))
  }

  private def openTerm(terms: List[SubscriptionTerm]): Option[SubscriptionTerm] = terms.find(_.effectiveTo.isEmpty)

  private def sortTerms(terms: List[SubscriptionTerm]): List[SubscriptionTerm] =
    terms.sortBy(_.effectiveFrom.toEpochDay)

  private def firstError[A](items: List[A])(check: A => Either[String, Unit]): Either[String, Unit] =
    items.foldLeft[Either[String, Unit]](Right(()))((acc, item) => acc.flatMap(_ => check(item)))
}
