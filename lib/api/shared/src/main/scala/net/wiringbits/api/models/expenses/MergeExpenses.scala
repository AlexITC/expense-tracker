package net.wiringbits.api.models.expenses

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

object MergeExpenses {

  /** Merges `loserId` into the expense in the path. `shape` is the duplicate shape the client detected; the server
    * rejects the merge when the expenses no longer have that shape.
    */
  case class Request(loserId: UUID, keepDetailsFrom: MergeDetailsSource, shape: MergeShape)
  case class Response()

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] = Schema.derived[Request].name(Schema.SName("MergeExpensesRequest"))
  implicit val responseSchema: Schema[Response] = Schema.derived[Response].name(Schema.SName("MergeExpensesResponse"))
}
