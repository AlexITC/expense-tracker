package net.wiringbits.api.models.savings

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetSavingsAllocations {
  case class Response(data: List[Response.Allocation])

  object Response {
    case class Allocation(
        id: UUID,
        amount: BigDecimal,
        date: LocalDate,
        note: Option[String],
        merchantId: Option[UUID],
        createdAt: Instant
    )

    implicit val allocationFormat: Format[Allocation] = Json.format[Allocation]
    implicit val allocationSchema: Schema[Allocation] =
      Schema.derived[Allocation].name(Schema.SName("GetSavingsAllocationsAllocation"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetSavingsAllocationsResponse"))
}
