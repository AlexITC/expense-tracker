package net.wiringbits.validations

import net.wiringbits.api.models.bankstatements.{ConfirmWarning, ConfirmWarningKind}
import net.wiringbits.repositories.models.BankStatement

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure, non-blocking sanity checks run before confirming a bank statement draft.
  *
  * They only produce warnings; periods and row dates are never altered. Long lists in messages show the first 5 items
  * followed by "+N more".
  */
object BankStatementConfirmChecks {

  private val MinPeriodDays = 26L
  private val MaxPeriodDays = 35L

  def periodWarnings(start: LocalDate, end: LocalDate, today: LocalDate): List[ConfirmWarning] = {
    val orderOrLength =
      if (start.isAfter(end))
        Some(
          ConfirmWarning(
            ConfirmWarningKind.PeriodOrder,
            s"The period start ($start) is after the period end ($end)."
          )
        )
      else {
        val days = ChronoUnit.DAYS.between(start, end)
        Option.when(days < MinPeriodDays || days > MaxPeriodDays)(
          ConfirmWarning(
            ConfirmWarningKind.PeriodLength,
            s"The period $start → $end spans $days days, which is unusual for a monthly statement."
          )
        )
      }

    val future = Option.when(end.isAfter(today))(
      ConfirmWarning(ConfirmWarningKind.FuturePeriod, s"The period end ($end) is in the future.")
    )

    orderOrLength.toList ++ future.toList
  }

  def overlapWarning(overlapping: List[BankStatement]): Option[ConfirmWarning] =
    Option.when(overlapping.nonEmpty) {
      val periods = overlapping.map(s => s"${s.periodStart} → ${s.periodEnd}").mkString(", ")
      ConfirmWarning(
        ConfirmWarningKind.Overlap,
        s"This period overlaps existing statements for the same account: $periods."
      )
    }

  /** Flags a product type or currency that differs from what earlier statements of the same card (bank + last4) were
    * saved with, which usually means the extraction misread it. `existing` should come from `findSameCard`; values
    * matching the current statement are ignored.
    */
  def cardMismatchWarnings(metadata: BankStatement.Metadata, existing: List[BankStatement]): List[ConfirmWarning] = {
    val card = s"••••${metadata.accountLast4}"
    val types = existing.map(_.productType).distinct.filterNot(_ == metadata.productType)
    val currencies = existing.map(_.currency).distinct.filterNot(_ == metadata.currency)

    val typeWarning = Option.when(types.nonEmpty)(
      ConfirmWarning(
        ConfirmWarningKind.ProductTypeMismatch,
        s"You've saved this card ($card) as ${types.mkString(", ")} before; this statement is ${metadata.productType} — if the type was misread, go back and change it."
      )
    )
    val currencyWarning = Option.when(currencies.nonEmpty)(
      ConfirmWarning(
        ConfirmWarningKind.CurrencyMismatch,
        s"You've saved this card ($card) in ${currencies.mkString(", ")} before; this statement is in ${metadata.currency} — if the currency was misread, go back and change it."
      )
    )

    typeWarning.toList ++ currencyWarning.toList
  }

  /** Flags rows dated before `periodStart` or after `periodEnd` (inclusive bounds). With a reversed period every row is
    * out of range, so the warning fires for all of them.
    */
  def rowsOutsidePeriodWarning(
      rowDates: List[LocalDate],
      periodStart: LocalDate,
      periodEnd: LocalDate
  ): Option[ConfirmWarning] = {
    val outside = rowDates.filter(d => d.isBefore(periodStart) || d.isAfter(periodEnd))
    Option.when(outside.nonEmpty) {
      val earliest = outside.minBy(_.toEpochDay)
      val latest = outside.maxBy(_.toEpochDay)
      ConfirmWarning(
        ConfirmWarningKind.RowsOutsidePeriod,
        s"${outside.size} of ${rowDates.size} rows are dated outside the stated period $periodStart → $periodEnd (earliest $earliest, latest $latest)."
      )
    }
  }

  /** Flags row dates that fall inside the period of an existing statement of the exact same account (bank + product
    * type + last4 + currency, as in `findOverlapping`). Statements in `sameCard` for other accounts are ignored. Fires
    * independently of the Overlap warning.
    */
  def rowsInOtherStatementWarning(
      metadata: BankStatement.Metadata,
      rowDates: List[LocalDate],
      sameCard: List[BankStatement]
  ): Option[ConfirmWarning] = {
    val distinctDates = rowDates.distinct.sortBy(_.toEpochDay)
    val groups = sameCard
      .filter(s =>
        s.bankName == metadata.bankName &&
          s.productType == metadata.productType &&
          s.accountLast4 == metadata.accountLast4 &&
          s.currency == metadata.currency
      )
      .flatMap { s =>
        val inside = distinctDates.filter(d => !d.isBefore(s.periodStart) && !d.isAfter(s.periodEnd))
        Option.when(inside.nonEmpty)(
          s"${capped(items = inside.map(_.toString), separator = ", ")} (in ${s.periodStart} → ${s.periodEnd})"
        )
      }

    Option.when(groups.nonEmpty)(
      ConfirmWarning(
        ConfirmWarningKind.RowsInOtherStatement,
        s"Some rows fall inside existing statements for the same account: ${capped(items = groups, separator = "; ")}."
      )
    )
  }

  /** Shows the first 5 items followed by "+N more" when there are more. */
  private def capped(items: List[String], separator: String): String = {
    val shown = items.take(5).mkString(separator)
    if (items.sizeIs > 5) s"$shown$separator+${items.size - 5} more" else shown
  }
}
