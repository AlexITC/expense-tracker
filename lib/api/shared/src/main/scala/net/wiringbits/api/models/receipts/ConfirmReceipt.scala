package net.wiringbits.api.models.receipts

import net.wiringbits.api.models.*
import net.wiringbits.api.models.expenses.CreateExpense
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{LocalDate, LocalTime}
import java.util.UUID

object ConfirmReceipt {
  case class Request(
      amount: Option[BigDecimal],
      date: Option[LocalDate],
      time: Option[LocalTime],
      merchantId: Option[UUID],
      notes: Option[String],
      splits: List[CreateExpense.Split],
      parsedMerchantName: Option[String] = None,
      rowId: Option[UUID] = None
  )

  case class Response(expenseId: Option[UUID])

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("ConfirmReceiptRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("ConfirmReceiptResponse"))
}
