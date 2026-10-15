package net.wiringbits.api.models.receipts

import net.wiringbits.api.models.*
import net.wiringbits.api.models.documents.DocumentStatus
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

object GetReceipt {
  case class Response(
      id: UUID,
      documentId: UUID,
      documentFilename: String,
      documentStatus: DocumentStatus,
      amount: Option[BigDecimal],
      date: Option[LocalDate],
      time: Option[LocalTime],
      merchantName: Option[String],
      taxId: Option[String] = None,
      expenseId: Option[UUID],
      ignoredAt: Option[Instant],
      manuallyEdited: Boolean,
      parserVersion: Option[String],
      createdAt: Instant
  )

  object Response {
    implicit val format: Format[Response] = Json.format[Response]
    implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetReceiptResponse"))
  }
}
