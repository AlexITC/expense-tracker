package net.wiringbits.api.models.bankstatementtemplates

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.LocalDate
import java.util.UUID

/** AI-based bank-statement extraction preview: the model reads the statement's native text and returns transactions +
  * metadata directly; the server verifies them against the statement's own arithmetic (running balance / opening ± sum
  * \= closing). Preview only — nothing is persisted.
  */
object AiExtractBankStatement {
  case class Request(bankStatementId: UUID)

  case class Transaction(
      date: LocalDate,
      amount: BigDecimal, // signed: negative = money out, positive = money in
      description: String,
      runningBalance: Option[BigDecimal],
      txnType: Option[String] // purchase | payment | fee | interest | refund
  )

  case class Metadata(
      bankName: Option[String],
      productType: Option[ProductType],
      currency: Option[String],
      accountLast4: Option[String],
      periodStart: Option[LocalDate],
      periodEnd: Option[LocalDate],
      openingBalance: Option[BigDecimal],
      closingBalance: Option[BigDecimal]
  )

  case class Response(
      reconciled: Boolean,
      discrepancy: Option[String], // human-readable gap when not reconciled
      model: String,
      transactions: List[Transaction],
      metadata: Metadata
  )

  object Transaction {
    implicit val format: Format[Transaction] = Json.format[Transaction]
    implicit val schema: Schema[Transaction] = Schema.derived[Transaction].name(Schema.SName("AiExtractTransaction"))
  }

  object Metadata {
    implicit val format: Format[Metadata] = Json.format[Metadata]
    implicit val schema: Schema[Metadata] = Schema.derived[Metadata].name(Schema.SName("AiExtractMetadata"))
  }

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val requestSchema: Schema[Request] = Schema.derived[Request].name(Schema.SName("AiExtractRequest"))
  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] = Schema.derived[Response].name(Schema.SName("AiExtractResponse"))
}
