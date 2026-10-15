package net.wiringbits.api.models.bankstatements

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{LocalDate, LocalTime}
import java.util.UUID

object CreateExpenseFromRow {
  case class Split(categoryId: UUID, amount: BigDecimal)
  object Split {
    implicit val format: Format[Split] = Json.format[Split]
    implicit val schema: Schema[Split] = Schema.derived[Split].name(Schema.SName("CreateExpenseFromRowSplit"))
  }

  case class Request(
      amount: BigDecimal,
      date: LocalDate,
      time: Option[LocalTime],
      merchantId: Option[UUID],
      notes: Option[String],
      splits: List[Split],
      receiptId: Option[UUID] = None
  )
  case class Response(expenseId: UUID)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("CreateExpenseFromRowRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateExpenseFromRowResponse"))
}
