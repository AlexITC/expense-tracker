package net.wiringbits.validations

import net.wiringbits.api.models.bankstatements.{ConfirmWarning, ConfirmWarningKind}
import net.wiringbits.repositories.models.BankStatement

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure, non-blocking sanity checks run before confirming a bank statement draft. */
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
    * saved with, which usually means the extraction misread it. `existing` should come from `findSameCardMismatches`;
    * values matching the current statement are ignored.
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
}
