package net.wiringbits.forms

import net.wiringbits.api.models.merchants.{CreateMerchant, UpdateMerchant}
import net.wiringbits.common.models.MerchantName
import net.wiringbits.ui.forms.{ExpNotes, ExpOptionalId, ExpTaxId}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.util.UUID

case class MerchantFormData(
    name: FormField[MerchantName],
    notes: FormField[Option[ExpNotes]],
    categoryId: FormField[Option[UUID]],
    taxId: FormField[Option[ExpTaxId]]
) extends FormData[CreateMerchant.Request] {
  override def fields: List[FormField[?]] = List(name, notes, categoryId, taxId)

  override def submitRequest: Option[CreateMerchant.Request] =
    for {
      n <- name.valueOpt
      no <- notes.valueOpt
      c <- categoryId.valueOpt
      t <- taxId.valueOpt
    } yield CreateMerchant.Request(
      name = n,
      notes = no.map(_.string),
      categoryId = c,
      taxId = t.map(_.string)
    )

  def updateRequest: Option[UpdateMerchant.Request] =
    submitRequest.map(request =>
      UpdateMerchant.Request(
        name = request.name,
        notes = request.notes,
        categoryId = request.categoryId,
        taxId = request.taxId
      )
    )
}

object MerchantFormData {

  private val NameLabel = "Merchant name"

  // The create and edit forms are on screen together, so `prefix` keeps the new fields' names (and DOM ids) distinct.
  def initial(
      prefix: String,
      name: Option[String],
      notes: String,
      categoryId: String,
      taxId: String
  ): MerchantFormData =
    MerchantFormData(
      name = new FormField(
        label = NameLabel,
        name = "name",
        required = true,
        value = name.map(MerchantName.validate)
      ),
      notes = new FormField(
        label = "Notes (optional)",
        name = s"$prefix-notes",
        required = false,
        value = Some(ExpNotes.optionalValidator(notes))
      ),
      categoryId = new FormField(
        label = "Default category",
        name = s"$prefix-categoryId",
        required = false,
        value = Some(ExpOptionalId.validate(categoryId))
      ),
      taxId = new FormField(
        label = "Tax ID (optional)",
        name = s"$prefix-taxId",
        required = false,
        value = Some(ExpTaxId.optionalValidator(taxId))
      )
    )
}
