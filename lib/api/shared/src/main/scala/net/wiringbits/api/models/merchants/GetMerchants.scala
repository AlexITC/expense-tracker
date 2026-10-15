package net.wiringbits.api.models.merchants

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.Instant
import java.util.UUID

object GetMerchants {
  case class Response(data: List[Response.Merchant])

  object Response {
    case class Merchant(
        id: UUID,
        name: String,
        notes: Option[String],
        categoryId: Option[UUID],
        taxId: Option[String] = None,
        reviewed: Boolean,
        createdAt: Instant
    )
    implicit val merchantFormat: Format[Merchant] = Json.format[Merchant]
  }

  implicit val format: Format[Response] = Json.format[Response]

  implicit val merchantSchema: Schema[Response.Merchant] =
    Schema.derived[Response.Merchant].name(Schema.SName("GetMerchantsMerchant"))
  implicit val schema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetMerchantsResponse"))
}
