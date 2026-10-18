package net.wiringbits.models.jobs

import net.wiringbits.common.models.Email
import play.api.libs.json.{Format, Json, Writes}

import java.util.UUID

sealed trait BackgroundJobPayload extends Product with Serializable

/** NOTE: Updating these models can cause tasks to fail, for example, adding an extra argument to SendEmail would cause
  * the json parsing to fail when we already have jobs in the database
  */
object BackgroundJobPayload {
  case class SendEmail(email: Email, subject: String, body: String) extends BackgroundJobPayload

  object SendEmail {
    implicit val sendEmailFormat: Format[SendEmail] = Json.format
  }

  case class ProcessDocument(documentId: UUID) extends BackgroundJobPayload

  object ProcessDocument {
    implicit val processDocumentFormat: Format[ProcessDocument] = Json.format
  }

  case class PollBankStatement(documentId: UUID) extends BackgroundJobPayload

  object PollBankStatement {
    implicit val pollBankStatementFormat: Format[PollBankStatement] = Json.format
  }

  case class ParseBankStatement(documentId: UUID) extends BackgroundJobPayload

  object ParseBankStatement {
    implicit val parseBankStatementFormat: Format[ParseBankStatement] = Json.format
  }

  case class PollReceipt(documentId: UUID) extends BackgroundJobPayload

  object PollReceipt {
    implicit val pollReceiptFormat: Format[PollReceipt] = Json.format
  }

  case class ParseReceipt(documentId: UUID) extends BackgroundJobPayload

  object ParseReceipt {
    implicit val parseReceiptFormat: Format[ParseReceipt] = Json.format
  }

  case class ExtractBankStatementDraft(draftId: UUID) extends BackgroundJobPayload

  object ExtractBankStatementDraft {
    implicit val extractBankStatementDraftFormat: Format[ExtractBankStatementDraft] = Json.format
  }

  case class ResolveStatementSuggestions(bankStatementId: UUID) extends BackgroundJobPayload

  object ResolveStatementSuggestions {
    implicit val resolveStatementSuggestionsFormat: Format[ResolveStatementSuggestions] = Json.format
  }

  implicit val backgroundJobPayloadWrites: Writes[BackgroundJobPayload] = {
    case payload: SendEmail => Json.toJson(payload)
    case payload: ProcessDocument => Json.toJson(payload)
    case payload: PollBankStatement => Json.toJson(payload)
    case payload: ParseBankStatement => Json.toJson(payload)
    case payload: PollReceipt => Json.toJson(payload)
    case payload: ParseReceipt => Json.toJson(payload)
    case payload: ExtractBankStatementDraft => Json.toJson(payload)
    case payload: ResolveStatementSuggestions => Json.toJson(payload)
  }
}
