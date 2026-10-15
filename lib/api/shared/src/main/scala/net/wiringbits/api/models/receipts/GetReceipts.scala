package net.wiringbits.api.models.receipts

import net.wiringbits.api.models.*
import net.wiringbits.api.models.documents.DocumentStatus
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetReceipts {
  case class Response(data: List[Response.Receipt])

  object Response {
    case class Receipt(
        id: UUID,
        documentFilename: String,
        documentStatus: DocumentStatus,
        amount: Option[BigDecimal],
        date: Option[LocalDate],
        merchantName: Option[String],
        taxId: Option[String] = None,
        merchantId: Option[UUID] = None,
        expenseId: Option[UUID],
        ignoredAt: Option[Instant],
        createdAt: Instant
    )

    implicit val receiptFormat: Format[Receipt] = Json.format[Receipt]
    implicit val receiptSchema: Schema[Receipt] =
      Schema.derived[Receipt].name(Schema.SName("GetReceiptsReceipt"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetReceiptsResponse"))
}
