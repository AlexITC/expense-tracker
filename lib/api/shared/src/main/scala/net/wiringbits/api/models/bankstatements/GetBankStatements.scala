package net.wiringbits.api.models.bankstatements

import net.wiringbits.api.models.*
import net.wiringbits.api.models.documents.DocumentStatus
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetBankStatements {
  case class Response(data: List[Response.BankStatement])

  object Response {
    case class BankStatement(
        id: UUID,
        documentFilename: String,
        documentStatus: DocumentStatus,
        bankName: String,
        productType: String,
        currency: String,
        accountLast4: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        createdAt: Instant,
        totalRows: Int,
        processedRows: Int
    )

    implicit val stmtFormat: Format[BankStatement] = Json.format[BankStatement]
    implicit val stmtSchema: Schema[BankStatement] =
      Schema.derived[BankStatement].name(Schema.SName("GetBankStatementsBankStatement"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetBankStatementsResponse"))
}
