package net.wiringbits.api.models.bankstatements

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetAllRows {
  case class RowData(
      id: UUID,
      bankStatementId: UUID,
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      expenseId: Option[UUID],
      ignoredAt: Option[Instant]
  )
  object RowData {
    implicit val format: Format[RowData] = Json.format[RowData]
    implicit val schema: Schema[RowData] = Schema.derived[RowData].name(Schema.SName("BankStatementAllRowData"))
  }

  case class Response(data: List[RowData])
  object Response {
    implicit val format: Format[Response] = Json.format[Response]
    implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetAllRowsResponse"))
  }
}
