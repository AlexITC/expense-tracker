package net.wiringbits.api.models.documents

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetDocument {

  case class StatementRowData(
      id: UUID,
      index: Int,
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      manuallyEdited: Boolean,
      confirmedAt: Option[Instant]
  )
  object StatementRowData {
    implicit val format: Format[StatementRowData] = Json.format[StatementRowData]
    implicit val schema: Schema[StatementRowData] =
      Schema.derived[StatementRowData].name(Schema.SName("StatementRowData"))
  }

  case class Response(
      id: UUID,
      sha256: String,
      kind: String,
      filename: String,
      statementRows: Option[List[StatementRowData]],
      createdAt: Instant
  )

  object Response {
    implicit val format: Format[Response] = Json.format[Response]
    implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetDocumentResponse"))
  }
}
