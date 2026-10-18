package net.wiringbits.api.models.bankstatements

import net.wiringbits.api.models.*
import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.LocalDate
import java.util.UUID

/** A draft bank-statement import: the AI extraction a user reviews/fixes before confirming it into a real statement.
  * `openingBalance`/`closingBalance` live only here (needed to re-check reconciliation on edits); they are not
  * persisted onto the final statement.
  */
object BankStatementDraft {
  case class Metadata(
      bankName: Option[String],
      productType: Option[String],
      currency: Option[String],
      accountLast4: Option[String],
      periodStart: Option[LocalDate],
      periodEnd: Option[LocalDate],
      openingBalance: Option[BigDecimal],
      closingBalance: Option[BigDecimal]
  )
  object Metadata {
    implicit val format: Format[Metadata] = Json.format[Metadata]
    implicit val schema: Schema[Metadata] = Schema.derived[Metadata].name(Schema.SName("BankStatementDraftMetadata"))
  }

  case class Transaction(
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      txnType: Option[String],
      runningBalance: Option[BigDecimal]
  )
  object Transaction {
    implicit val format: Format[Transaction] = Json.format[Transaction]
    implicit val schema: Schema[Transaction] =
      Schema.derived[Transaction].name(Schema.SName("BankStatementDraftTransaction"))
  }

  case class Payload(
      metadata: Metadata,
      transactions: List[Transaction],
      reconciled: Boolean,
      discrepancy: Option[String],
      model: String,
      stage: Option[ExtractionSource]
  )
  object Payload {
    implicit val format: Format[Payload] = Json.format[Payload]
    implicit val schema: Schema[Payload] = Schema.derived[Payload].name(Schema.SName("BankStatementDraftPayload"))
  }

  // status: "processing" (extraction running) | "ready" (payload present) | "failed" (error present)
  case class Data(id: UUID, filename: String, status: String, error: Option[String], payload: Option[Payload])
  object Data {
    implicit val format: Format[Data] = Json.format[Data]
    implicit val schema: Schema[Data] = Schema.derived[Data].name(Schema.SName("BankStatementDraftData"))
  }

  // Lightweight row for the drafts queue (no payload).
  case class Summary(id: UUID, filename: String, status: String, error: Option[String])
  object Summary {
    implicit val format: Format[Summary] = Json.format[Summary]
    implicit val schema: Schema[Summary] = Schema.derived[Summary].name(Schema.SName("BankStatementDraftSummary"))
  }

  object Listing {
    case class Response(drafts: List[Summary])
    implicit val format: Format[Response] = Json.format[Response]
    implicit val schema: Schema[Response] =
      Schema.derived[Response].name(Schema.SName("ListBankStatementDraftsResponse"))
  }

  object Update {
    case class Request(payload: Payload)
    implicit val format: Format[Request] = Json.format[Request]
    implicit val schema: Schema[Request] =
      Schema.derived[Request].name(Schema.SName("UpdateBankStatementDraftRequest"))
  }

  object Confirm {
    case class Response(bankStatementId: UUID)
    implicit val format: Format[Response] = Json.format[Response]
    implicit val schema: Schema[Response] =
      Schema.derived[Response].name(Schema.SName("ConfirmBankStatementDraftResponse"))
  }

  object Discard {
    case class Response(discarded: Boolean)
    implicit val format: Format[Response] = Json.format[Response]
    implicit val schema: Schema[Response] =
      Schema.derived[Response].name(Schema.SName("DiscardBankStatementDraftResponse"))
  }
}
