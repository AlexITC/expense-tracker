package net.wiringbits.api.models.expenses

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

object GetExpenses {
  case class Response(data: List[Response.Expense])

  object Response {
    case class Split(id: UUID, categoryId: UUID, amount: BigDecimal)
    object Split {
      implicit val format: Format[Split] = Json.format[Split]
      implicit val schema: Schema[Split] = Schema.derived[Split].name(Schema.SName("GetExpensesSplit"))
    }

    case class Expense(
        id: UUID,
        amount: BigDecimal,
        date: LocalDate,
        time: Option[LocalTime],
        merchantId: Option[UUID],
        merchantName: Option[String],
        notes: Option[String],
        splits: List[Split],
        createdAt: Instant,
        receiptIds: List[UUID] = Nil,
        rowIds: List[UUID] = Nil
    )
    implicit val expenseFormat: Format[Expense] = Json.format[Expense]
    implicit val expenseSchema: Schema[Expense] = Schema.derived[Expense].name(Schema.SName("GetExpensesExpense"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetExpensesResponse"))
}
