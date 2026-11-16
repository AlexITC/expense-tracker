package net.wiringbits.forms

import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.refunds.GetRefunds
import net.wiringbits.ui.forms.{ExistingSelections, ExpLocalDate, ExpNotes, ExpOptionalId, PositiveBigDecimal}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate
import java.util.UUID

/** The create/edit form of the refunds page: a required positive amount and date, plus optional existing-only
  * merchant/category picks (validated ids, since the page uses selects, not pick-or-create) and optional validated
  * notes.
  */
case class RefundFormData(
    amount: FormField[PositiveBigDecimal],
    date: FormField[ExpLocalDate],
    merchantId: FormField[Option[UUID]],
    categoryId: FormField[Option[UUID]],
    notes: FormField[Option[ExpNotes]]
) extends FormData[RefundFormData.Resolved] {
  override def fields: List[FormField[?]] = List(amount, date, merchantId, categoryId, notes)

  override def submitRequest: Option[RefundFormData.Resolved] =
    for {
      a <- amount.valueOpt
      d <- date.valueOpt
      m <- merchantId.valueOpt
      c <- categoryId.valueOpt
      n <- notes.valueOpt
    } yield RefundFormData.Resolved(
      amount = a.value,
      date = d.value,
      merchantId = m,
      categoryId = c,
      notes = n.map(_.string)
    )

  /** Picks the merchant and sets the category to its default (clearing it when the merchant has none). */
  def withMerchant(
      value: ValidationResult[Option[UUID]],
      merchants: List[GetMerchants.Response.Merchant]
  ): RefundFormData =
    copy(
      merchantId = merchantId.updated(value),
      categoryId = categoryId.updated(
        ExpOptionalId.validate(
          ExistingSelections.defaultCategoryId(value.toOption.flatten, merchants).fold("")(_.toString)
        )
      )
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

  private def merchantField(prefix: String, raw: String): FormField[Option[UUID]] =
    new FormField(
      label = "Merchant (optional)",
      name = s"$prefix-merchant",
      required = false,
      value = Some(ExpOptionalId.validate(raw))
    )

  private def categoryField(prefix: String, raw: String): FormField[Option[UUID]] =
    new FormField(
      label = "Category (optional)",
      name = s"$prefix-category",
      required = false,
      value = Some(ExpOptionalId.validate(raw))
    )

  private def notesField(prefix: String, raw: String): FormField[Option[ExpNotes]] =
    new FormField(
      label = "Notes (optional)",
      name = s"$prefix-notes",
      required = false,
      value = Some(ExpNotes.optionalValidator(raw))
    )

  val initial: RefundFormData =
    RefundFormData(
      amount = amountField("refund-create", None),
      date = dateField("refund-create", None),
      merchantId = merchantField(prefix = "refund-create", raw = ""),
      categoryId = categoryField(prefix = "refund-create", raw = ""),
      notes = notesField(prefix = "refund-create", raw = "")
    )

  def fromRefund(r: GetRefunds.Response.Refund): RefundFormData =
    RefundFormData(
      amount = amountField("refund-edit", Some(r.amount.toString)),
      date = dateField("refund-edit", Some(r.date.toString)),
      merchantId = merchantField(prefix = "refund-edit", raw = r.merchantId.map(_.toString).getOrElse("")),
      categoryId = categoryField(prefix = "refund-edit", raw = r.categoryId.map(_.toString).getOrElse("")),
      notes = notesField(prefix = "refund-edit", raw = r.notes.getOrElse(""))
    )
}
