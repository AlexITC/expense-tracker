package net.wiringbits.forms

import net.wiringbits.api.models.merchants.{CreateMerchant, UpdateMerchant}
import net.wiringbits.common.models.MerchantName
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.util.UUID
import scala.util.Try

case class MerchantFormData(
    name: FormField[MerchantName],
    notes: String,
    categoryId: String,
    taxId: String
) extends FormData[CreateMerchant.Request] {
  override def fields: List[FormField[?]] = List(name)

  override def formValidationErrors: List[String] = List(fieldsError).flatten

  // notes and tax id are optional free text, so a blank field clears them; their length is validated by the action.
  private def parsedCategoryId: Option[UUID] =
    if (categoryId.isEmpty) None else Try(UUID.fromString(categoryId)).toOption

  override def submitRequest: Option[CreateMerchant.Request] =
    name.valueOpt.map { merchantName =>
      CreateMerchant.Request(
        name = merchantName,
        notes = Option(notes.trim).filter(_.nonEmpty),
        categoryId = parsedCategoryId,
        taxId = Option(taxId.trim).filter(_.nonEmpty)
      )
    }

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

  def initial(name: Option[String], notes: String, categoryId: String, taxId: String): MerchantFormData =
    MerchantFormData(
      name = new FormField(
        label = NameLabel,
        name = "name",
        required = true,
        value = name.map(MerchantName.validate)
      ),
      notes = notes,
      categoryId = categoryId,
      taxId = taxId
    )
}
