package net.wiringbits.forms

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.ui.forms.{ExpDraftDescription, ExpDraftTxnType, ExpLocalDate, ExpSignedAmount}
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField

/** One transaction row of the bank statement draft editor. `key` is a stable row identity (not edited by the user) used
  * for unique field names and the React key, so inserting or removing a row doesn't shift the others' identities.
  */
case class DraftTransactionRow(
    key: Int,
    date: FormField[ExpLocalDate],
    amount: FormField[ExpSignedAmount],
    description: FormField[ExpDraftDescription],
    txnType: FormField[Option[ExpDraftTxnType]],
    runningBalance: FormField[Option[ExpSignedAmount]]
)

object DraftTransactionRow {
  private def name(key: Int, field: String): String = s"draft-txn-$key-$field"

  private def build(
      key: Int,
      date: Option[String],
      amount: Option[String],
      description: Option[String],
      txnType: String,
      runningBalance: String
  ): DraftTransactionRow =
    DraftTransactionRow(
      key = key,
      date = new FormField(
        label = "Date",
        name = name(key, "date"),
        required = true,
        value = date.map(ExpLocalDate.validate)
      ),
      amount = new FormField(
        label = "Amount",
        name = name(key, "amount"),
        required = true,
        value = amount.map(ExpSignedAmount.validate)
      ),
      description = new FormField(
        label = "Description",
        name = name(key, "description"),
        required = true,
        value = description.map(ExpDraftDescription.validate)
      ),
      txnType = new FormField(
        label = "Type",
        name = name(key, "type"),
        required = false,
        value = Some(ExpDraftTxnType.optionalValidator(txnType))
      ),
      runningBalance = new FormField(
        label = "Running balance",
        name = name(key, "running-balance"),
        required = false,
        value = Some(ExpSignedAmount.optionalValidator(runningBalance))
      )
    )

  def blank(key: Int): DraftTransactionRow =
    build(key = key, date = None, amount = None, description = None, txnType = "", runningBalance = "")

  /** Seeds with the raw values, so anything over a limit surfaces as a field error instead of being silently cut. */
  def seeded(key: Int, t: BankStatementDraft.Transaction): DraftTransactionRow =
    build(
      key = key,
      date = Some(t.date.toString),
      amount = Some(t.amount.bigDecimal.toPlainString),
      description = Some(t.description),
      txnType = t.txnType.getOrElse(""),
      runningBalance = t.runningBalance.map(_.bigDecimal.toPlainString).getOrElse("")
    )

  def fromTransactions(transactions: List[BankStatementDraft.Transaction]): List[DraftTransactionRow] =
    transactions.zipWithIndex.map { case (t, i) => seeded(key = i, t = t) }

  def nextKey(rows: List[DraftTransactionRow]): Int = rows.map(_.key).maxOption.fold(0)(_ + 1)

  def canInsert(rows: List[DraftTransactionRow]): Boolean = rows.size < BankStatementDraftLimits.MaxTransactions

  /** Adds a blank row right after the row with `key`; a no-op at the cap or when `key` is unknown. */
  def insertAfter(rows: List[DraftTransactionRow], key: Int): List[DraftTransactionRow] =
    rows.indexWhere(_.key == key) match {
      case index if index >= 0 && canInsert(rows) =>
        val (before, after) = rows.splitAt(index + 1)
        before ++ (blank(nextKey(rows)) :: after)
      case _ => rows
    }

  /** Adds a blank row at the top; a no-op at the cap. */
  def insertAtTop(rows: List[DraftTransactionRow]): List[DraftTransactionRow] =
    if (canInsert(rows)) blank(nextKey(rows)) :: rows else rows

  def remove(rows: List[DraftTransactionRow], key: Int): List[DraftTransactionRow] = rows.filterNot(_.key == key)

  def update(
      rows: List[DraftTransactionRow],
      key: Int,
      f: DraftTransactionRow => DraftTransactionRow
  ): List[DraftTransactionRow] =
    rows.map(r => if (r.key == key) f(r) else r)

  def fields(rows: List[DraftTransactionRow]): List[FormField[?]] =
    rows.flatMap(r => List(r.date, r.amount, r.description, r.txnType, r.runningBalance))

  /** The rows as draft transactions, or None while any row is invalid or a required field is empty. */
  def resolve(rows: List[DraftTransactionRow]): Option[List[BankStatementDraft.Transaction]] =
    rows.foldRight(Option(List.empty[BankStatementDraft.Transaction])) { (row, acc) =>
      for {
        rest <- acc
        date <- row.date.valueOpt
        amount <- row.amount.valueOpt
        description <- row.description.valueOpt
        txnType <- row.txnType.valueOpt
        runningBalance <- row.runningBalance.valueOpt
      } yield BankStatementDraft.Transaction(
        date = date.value,
        amount = amount.value,
        description = description.string,
        txnType = txnType.map(_.string),
        runningBalance = runningBalance.map(_.value)
      ) :: rest
    }
}
