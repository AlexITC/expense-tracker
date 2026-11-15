package net.wiringbits.forms

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.ui.forms.{ExpLocalDate, ExpLocalTime, ExpNotes, ExpOptionalId, PositiveBigDecimal}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.{LocalDate, LocalTime}
import java.util.UUID

/** The create/edit form of the expenses page: a required positive amount and date, an optional time, merchant and
  * notes, plus the category split rows. Every input is a validated field. The splits' cross-field rule (they must cover
  * the amount) is checked by the page at submit, not here.
  *
  * `prefix` keeps field names (and thus DOM ids) unique, since the create and edit forms can be on screen together.
  */
case class ExpenseFormData(
    prefix: String,
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    time: FormField[Option[ExpLocalTime]],
    merchantId: FormField[Option[UUID]],
    notes: FormField[Option[ExpNotes]],
    splits: List[ExpenseSplitRow]
) extends FormData[ExpenseFormData.Resolved] {
  override def fields: List[FormField[?]] =
    List(amount, date, time, merchantId, notes) ++ splits.flatMap(r => List(r.category, r.amount))

  override def submitRequest: Option[ExpenseFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
      t <- time.valueOpt
      m <- merchantId.valueOpt
      n <- notes.valueOpt
      s <- resolvedSplits
    } yield ExpenseFormData.Resolved(
      amount = a.value,
      date = d.value,
      time = t.map(_.value),
      merchantId = m,
      notes = n.map(_.string),
      splits = s
    )

  private def resolvedSplits: Option[List[(Option[UUID], BigDecimal)]] =
    splits.foldRight(Option(List.empty[(Option[UUID], BigDecimal)])) { (row, acc) =>
      for {
        rest <- acc
        c <- row.category.valueOpt
        a <- row.amount.valueOpt
      } yield (c, a.value) :: rest
    }

  def addSplit: ExpenseFormData =
    copy(splits = splits :+ ExpenseSplitRow.blank(prefix = prefix, key = ExpenseSplitRow.nextKey(splits)))

  def removeSplit(key: Int): ExpenseFormData = copy(splits = splits.filterNot(_.key == key))

  def updateSplit(key: Int, f: ExpenseSplitRow => ExpenseSplitRow): ExpenseFormData =
    copy(splits = splits.map(r => if (r.key == key) f(r) else r))
}

object ExpenseFormData {
  case class Resolved(
      amount: BigDecimal,
      date: LocalDate,
      time: Option[LocalTime],
      merchantId: Option[UUID],
      notes: Option[String],
      splits: List[(Option[UUID], BigDecimal)]
  )

  private def build(
      prefix: String,
      amount: Option[String],
      date: Option[String],
      time: String,
      merchantId: String,
      notes: String,
      splits: List[(String, String)]
  ): ExpenseFormData =
    ExpenseFormData(
      prefix = prefix,
      amount = new FormField(
        label = "Amount",
        name = s"$prefix-amount",
        required = true,
        value = amount.map(PositiveBigDecimal.validate)
      ),
      date = new FormField(
        label = "Date (YYYY-MM-DD)",
        name = s"$prefix-date",
        required = true,
        value = date.map(ExpLocalDate.validate)
      ),
      time = new FormField(
        label = "Time (HH:MM, optional)",
        name = s"$prefix-time",
        required = false,
        value = Some(ExpLocalTime.optionalValidator(time))
      ),
      merchantId = new FormField(
        label = "Merchant (optional)",
        name = s"$prefix-merchant",
        required = false,
        value = Some(ExpOptionalId.validate(merchantId))
      ),
      notes = new FormField(
        label = "Notes (optional)",
        name = s"$prefix-notes",
        required = false,
        value = Some(ExpNotes.optionalValidator(notes))
      ),
      splits = splits.zipWithIndex.map { case ((catId, amt), i) =>
        ExpenseSplitRow.seeded(prefix = prefix, key = i, categoryId = catId, amount = amt)
      }
    )

  val initial: ExpenseFormData =
    build(prefix = "expense-create", amount = None, date = None, time = "", merchantId = "", notes = "", splits = Nil)

  def fromExpense(e: GetExpenses.Response.Expense): ExpenseFormData =
    build(
      prefix = "expense-edit",
      amount = Some(e.amount.toString),
      date = Some(e.date.toString),
      time = e.time.fold("")(_.toString),
      merchantId = e.merchantId.fold("")(_.toString),
      notes = e.notes.getOrElse(""),
      splits = e.splits.map(s => (s.categoryId.toString, s.amount.toString))
    )
}
