package net.wiringbits.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.receipts.ReceiptsLogic
import net.wiringbits.ui.forms.{ExpLocalDate, PositiveBigDecimal}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate
import java.util.UUID

/** The create-expense form for a receipt being confirmed: a required positive amount and date, plus plain free-text
  * values. `splits` holds (categoryId, categoryInput, amount).
  */
case class ReceiptConfirmFormData(
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    time: String,
    merchantId: String,
    merchantInput: String,
    notes: String,
    splits: List[(String, String, String)],
    parsedMerchantName: Option[String],
    rowId: Option[UUID]
) extends FormData[ReceiptConfirmFormData.Resolved] {
  override def fields: List[FormField[?]] = List(amount, date)

  override def submitRequest: Option[ReceiptConfirmFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
    } yield ReceiptConfirmFormData.Resolved(
      amount = a.value,
      date = d.value,
      time = time,
      merchantId = merchantId,
      notes = notes,
      splits = splits,
      parsedMerchantName = parsedMerchantName,
      rowId = rowId
    )
}

object ReceiptConfirmFormData {
  case class Resolved(
      amount: BigDecimal,
      date: LocalDate,
      time: String,
      merchantId: String,
      notes: String,
      splits: List[(String, String, String)],
      parsedMerchantName: Option[String],
      rowId: Option[UUID]
  )

  /** A fresh form prefilled from the receipt; the merchant comes from a tax-id match, else the winning rule. A rule's
    * override category prefills a split for the full amount and its description prefills the notes.
    */
  def fromReceipt(
      r: GetReceipts.Response.Receipt,
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      rules: List[ImportRuleMatcher.Rule]
  ): ReceiptConfirmFormData = {
    val resolved = ReceiptsLogic.resolveMerchant(r, merchants, rules)
    val amount = r.amount.map(_.toString).getOrElse("")
    val splits = resolved
      .flatMap(_.categoryId)
      .flatMap(cid => categories.find(_.id == cid))
      .map(c => (c.id.toString, c.name, amount))
      .toList
    ReceiptConfirmFormData(
      amount = new FormField(
        label = "Amount",
        name = "receipt-confirm-amount",
        required = true,
        value = Some(PositiveBigDecimal.validate(amount))
      ),
      date = new FormField(
        label = "Date (YYYY-MM-DD)",
        name = "receipt-confirm-date",
        required = true,
        value = Some(ExpLocalDate.validate(r.date.map(_.toString).getOrElse("")))
      ),
      time = "",
      merchantId = resolved.map(_.merchantId.toString).getOrElse(""),
      merchantInput = resolved.flatMap(m => merchants.find(_.id == m.merchantId)).map(_.name).getOrElse(""),
      notes = resolved.flatMap(_.description).getOrElse(""),
      splits = splits,
      parsedMerchantName = r.merchantName,
      rowId = None
    )
  }
}
