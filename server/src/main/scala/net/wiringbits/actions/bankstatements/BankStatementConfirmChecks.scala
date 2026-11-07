package net.wiringbits.actions.bankstatements

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
}
