package net.wiringbits.api.models.documents

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.Instant
import java.util.UUID

object GetDocuments {
  case class Response(data: List[Response.Document])

  object Response {
    case class Document(
        id: UUID,
        sha256: String,
        kind: String,
        filename: String,
        createdAt: Instant
    )

    implicit val documentFormat: Format[Document] = Json.format[Document]
    implicit val documentSchema: Schema[Document] =
      Schema.derived[Document].name(Schema.SName("GetDocumentsDocument"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetDocumentsResponse"))
}
