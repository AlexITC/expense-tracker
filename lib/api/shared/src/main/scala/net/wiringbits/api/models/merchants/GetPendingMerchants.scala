package net.wiringbits.api.models.merchants

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.Instant
import java.util.UUID

object GetPendingMerchants {
  case class Merchant(id: UUID, name: String, createdAt: Instant)
  case class Response(data: List[Merchant])

  object Merchant {
    implicit val format: Format[Merchant] = Json.format[Merchant]
    implicit val schema: Schema[Merchant] =
      Schema.derived[Merchant].name(Schema.SName("GetPendingMerchantsMerchant"))
  }

  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetPendingMerchantsResponse"))
}
