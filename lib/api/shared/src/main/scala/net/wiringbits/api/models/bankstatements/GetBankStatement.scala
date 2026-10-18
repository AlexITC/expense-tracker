package net.wiringbits.api.models.bankstatements

import net.wiringbits.api.models.*
import net.wiringbits.api.models.documents.DocumentStatus
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetBankStatement {
  case class StatementRowData(
      id: UUID,
      index: Int,
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      manuallyEdited: Boolean,
      confirmedAt: Option[Instant],
      expenseId: Option[UUID],
      refundId: Option[UUID],
      ignoredAt: Option[Instant],
      txnType: String
  )
  object StatementRowData {
    implicit val format: Format[StatementRowData] = Json.format[StatementRowData]
    implicit val schema: Schema[StatementRowData] =
      Schema.derived[StatementRowData].name(Schema.SName("BankStatementRowData"))
  }

  case class Response(
      id: UUID,
      documentId: UUID,
      documentFilename: String,
      documentStatus: DocumentStatus,
      bankName: String,
      productType: String,
      currency: String,
      accountLast4: String,
      periodStart: LocalDate,
      periodEnd: LocalDate,
      statementRows: List[StatementRowData],
      createdAt: Instant,
      extractionInfo: Option[ExtractionInfo],
      aiSuggestions: Option[AiSuggestions]
  )

  object Response {
    implicit val format: Format[Response] = Json.format[Response]
    implicit val schema: Schema[Response] =
      Schema.derived[Response].name(Schema.SName("GetBankStatementResponse"))
  }
}
