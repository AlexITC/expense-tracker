package net.wiringbits.forms

import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.refunds.GetRefunds
import net.wiringbits.ui.forms.{ExistingSelections, ExpLocalDate, PositiveBigDecimal}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate
import java.util.UUID

/** The create/edit form of the refunds page: a required positive amount and date, plus optional existing-only
  * merchant/category picks (plain ids, since the page uses selects, not pick-or-create) and free-text notes.
  */
case class RefundFormData(
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    merchantId: Option[UUID],
    categoryId: Option[UUID],
    notes: String
) extends FormData[RefundFormData.Resolved] {
  override def fields: List[FormField[?]] = List(amount, date)

  override def submitRequest: Option[RefundFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
    } yield RefundFormData.Resolved(
      amount = a.value,
      date = d.value,
      merchantId = merchantId,
      categoryId = categoryId,
      notes = Some(notes.trim).filter(_.nonEmpty)
    )

  /** Picks the merchant and sets the category to its default (clearing it when the merchant has none). */
  def withMerchant(id: Option[UUID], merchants: List[GetMerchants.Response.Merchant]): RefundFormData =
    copy(
      merchantId = id,
      categoryId = ExistingSelections.defaultCategoryId(id, merchants)
    )
}

object RefundFormData {
  case class Resolved(
      amount: BigDecimal,
      date: LocalDate,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      notes: Option[String]
  )

  // The create and edit forms are on screen together, so each gets its own field names (and thus DOM ids).
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

  val initial: RefundFormData =
    RefundFormData(
      amount = amountField("refund-create", None),
      date = dateField("refund-create", None),
      merchantId = None,
      categoryId = None,
      notes = ""
    )

  def fromRefund(r: GetRefunds.Response.Refund): RefundFormData =
    RefundFormData(
      amount = amountField("refund-edit", Some(r.amount.toString)),
      date = dateField("refund-edit", Some(r.date.toString)),
      merchantId = r.merchantId,
      categoryId = r.categoryId,
      notes = r.notes.getOrElse("")
    )
}
