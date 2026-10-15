package net.wiringbits.api.models.refunds

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetRefunds {
  case class Response(data: List[Response.Refund])

  object Response {
    case class Refund(
        id: UUID,
        amount: BigDecimal,
        date: LocalDate,
        merchantId: Option[UUID],
        merchantName: Option[String],
        categoryId: Option[UUID],
        categoryName: Option[String],
        expenseId: Option[UUID],
        notes: Option[String],
        createdAt: Instant
    )
    implicit val refundFormat: Format[Refund] = Json.format[Refund]
    implicit val refundSchema: Schema[Refund] = Schema.derived[Refund].name(Schema.SName("GetRefundsRefund"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetRefundsResponse"))
}
