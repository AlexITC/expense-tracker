package net.wiringbits.forms

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.ui.forms.{ExpLocalDate, PositiveBigDecimal}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate

/** The create/edit form of the expenses page: a required positive amount and date, plus the raw time, merchant select
  * value, notes and split rows. The splits' cross-field rule (they must cover the amount) is checked by the page at
  * submit, not here.
  */
case class ExpenseFormData(
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    time: String,
    merchantId: String,
    notes: String,
    splits: List[(String, String)]
) extends FormData[ExpenseFormData.Resolved] {
  override def fields: List[FormField[?]] = List(amount, date)

  override def submitRequest: Option[ExpenseFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
    } yield ExpenseFormData.Resolved(
      amount = a.value,
      date = d.value,
      time = time,
      merchantId = merchantId,
      notes = Some(notes.trim).filter(_.nonEmpty),
      splits = splits
    )
}

object ExpenseFormData {
  case class Resolved(
      amount: BigDecimal,
      date: LocalDate,
      time: String,
      merchantId: String,
      notes: Option[String],
      splits: List[(String, String)]
  )

  // The create and edit forms can be on screen together, so each gets its own field names (and thus DOM ids).
  private def amountField(prefix: String, value: Option[String]): FormField[PositiveBigDecimal] =
    new FormField(
      label = "Amount",
      name = s"$prefix-amount",
      required = true,
      value = value.map(PositiveBigDecimal.validate)
    )

  private def dateField(prefix: String, value: Option[String]): FormField[ExpLocalDate] =
    new FormField(
      label = "Date (YYYY-MM-DD)",
      name = s"$prefix-date",
      required = true,
      value = value.map(ExpLocalDate.validate)
    )

  val initial: ExpenseFormData =
    ExpenseFormData(
      amount = amountField("expense-create", None),
      date = dateField("expense-create", None),
      time = "",
      merchantId = "",
      notes = "",
      splits = Nil
    )

  def fromExpense(e: GetExpenses.Response.Expense): ExpenseFormData =
    ExpenseFormData(
      amount = amountField("expense-edit", Some(e.amount.toString)),
      date = dateField("expense-edit", Some(e.date.toString)),
      time = e.time.map(_.toString).getOrElse(""),
      merchantId = e.merchantId.map(_.toString).getOrElse(""),
      notes = e.notes.getOrElse(""),
      splits = e.splits.map(s => (s.categoryId.toString, s.amount.toString))
    )
}
