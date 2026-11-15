package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.common.models.MerchantName
import net.wiringbits.components.pages.receipts.ReceiptsLogic
import net.wiringbits.ui.forms.{
  ExistingOrNewValue,
  ExistingSelections,
  ExpLocalDate,
  ExpLocalTime,
  ExpNotes,
  PositiveBigDecimal
}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.{LocalDate, LocalTime}
import java.util.UUID

/** The create-expense form for a receipt being confirmed: a required positive amount and date, an optional time, a
  * pick-or-create merchant and notes, plus the category split rows. Every input is a validated field; `rowId` is the
  * bank row picked to attach, not a typed input. Resolving or creating the merchant stays a side effect of the page, as
  * does the splits' cross-field rule (they must cover the amount).
  */
case class ReceiptConfirmFormData(
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    time: FormField[Option[ExpLocalTime]],
    merchant: FormField[Option[ExistingOrNewValue[MerchantName, UUID]]],
    notes: FormField[Option[ExpNotes]],
    splits: List[ExpenseSplitRow],
    rowId: Option[UUID]
) extends FormData[ReceiptConfirmFormData.Resolved] {
  override def fields: List[FormField[?]] =
    List(amount, date, time, merchant, notes) ++ splits.flatMap(r => List(r.category, r.amount))

  override def submitRequest: Option[ReceiptConfirmFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
      t <- time.valueOpt
      m <- merchant.valueOpt
      n <- notes.valueOpt
      s <- resolvedSplits
    } yield ReceiptConfirmFormData.Resolved(
      amount = a.value,
      date = d.value,
      time = t.map(_.value),
      merchant = m,
      notes = n.map(_.string),
      splits = s,
      rowId = rowId
    )

  private def resolvedSplits: Option[List[(Option[UUID], BigDecimal)]] =
    splits.foldRight(Option(List.empty[(Option[UUID], BigDecimal)])) { (row, acc) =>
      for {
        rest <- acc
        c <- row.category.valueOpt
        a <- row.amount.valueOpt
      } yield (c, a.value) :: rest
    }

  def addSplit: ReceiptConfirmFormData =
    copy(splits =
      splits :+ ExpenseSplitRow.blank(prefix = ReceiptConfirmFormData.Prefix, key = ExpenseSplitRow.nextKey(splits))
    )

  def removeSplit(key: Int): ReceiptConfirmFormData = copy(splits = splits.filterNot(_.key == key))

  def updateSplit(key: Int, f: ExpenseSplitRow => ExpenseSplitRow): ReceiptConfirmFormData =
    copy(splits = splits.map(r => if (r.key == key) f(r) else r))

  /** Attaches the id of a merchant created by a submit, so a retry after a later failure reuses it. */
  def withResolvedMerchant(id: UUID): ReceiptConfirmFormData =
    merchant.value match {
      case Some(ValidationResult.Valid(input, Some(selection))) if selection.id.isEmpty =>
        copy(merchant = merchant.updated(ValidationResult.Valid(input, Some(selection.copy(id = Some(id))))))
      case _ => this
    }
}

object ReceiptConfirmFormData {
  private val Prefix = "receipt-confirm"

  case class Resolved(
      amount: BigDecimal,
      date: LocalDate,
      time: Option[LocalTime],
      merchant: Option[ExistingOrNewValue[MerchantName, UUID]],
      notes: Option[String],
      splits: List[(Option[UUID], BigDecimal)],
      rowId: Option[UUID]
  )

  private def asOptional[T](result: ValidationResult[T]): ValidationResult[Option[T]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }

  /** A fresh form prefilled from the receipt; the merchant comes from a tax-id match, else the winning rule, and is
    * left blank when that merchant isn't in the list. A rule's override category prefills a split for the full amount
    * and its description prefills the notes.
    */
  def fromReceipt(
      r: GetReceipts.Response.Receipt,
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      rules: List[ImportRuleMatcher.Rule]
  ): ReceiptConfirmFormData = {
    val resolved = ReceiptsLogic.resolveMerchant(r, merchants, rules)
    val amount = r.amount.map(_.toString).getOrElse("")
    val merchantSeed: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]] =
      resolved.flatMap(m => merchants.find(_.id == m.merchantId)) match {
        case Some(m) => asOptional(ExistingSelections.merchant(m))
        case None => ValidationResult.Valid("", None)
      }
    val splits = resolved
      .flatMap(_.categoryId)
      .flatMap(cid => categories.find(_.id == cid))
      .map(c => ExpenseSplitRow.seeded(prefix = Prefix, key = 0, categoryId = c.id.toString, amount = amount))
      .toList
    ReceiptConfirmFormData(
      amount = new FormField(
        label = "Amount",
        name = s"$Prefix-amount",
        required = true,
        value = Some(PositiveBigDecimal.validate(amount))
      ),
      date = new FormField(
        label = "Date (YYYY-MM-DD)",
        name = s"$Prefix-date",
        required = true,
        value = Some(ExpLocalDate.validate(r.date.map(_.toString).getOrElse("")))
      ),
      time = new FormField(
        label = "Time (HH:MM, optional)",
        name = s"$Prefix-time",
        required = false,
        value = Some(ExpLocalTime.optionalValidator(""))
      ),
      merchant = new FormField(
        label = "Merchant (optional)",
        name = s"$Prefix-merchant",
        required = false,
        value = Some(merchantSeed)
      ),
      notes = new FormField(
        label = "Notes (optional)",
        name = s"$Prefix-notes",
        required = false,
        value = Some(ExpNotes.optionalValidator(resolved.flatMap(_.description).getOrElse("")))
      ),
      splits = splits,
      rowId = None
    )
  }
}
