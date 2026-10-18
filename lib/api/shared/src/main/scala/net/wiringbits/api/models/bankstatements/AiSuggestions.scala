package net.wiringbits.api.models.bankstatements

import play.api.libs.json.*
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

/** AI-suggested categories and merchants for a statement's rows, and the model that produced them. */
case class AiSuggestions(
    status: AiSuggestionsStatus,
    model: String,
    rows: List[AiSuggestions.RowSuggestion]
)

object AiSuggestions {
  case class RowSuggestion(rowId: UUID, categoryId: Option[UUID], merchantId: Option[UUID])

  object RowSuggestion {
    implicit val format: Format[RowSuggestion] = Json.format[RowSuggestion]
    implicit val schema: Schema[RowSuggestion] =
      Schema.derived[RowSuggestion].name(Schema.SName("BankStatementAiRowSuggestion"))
  }

  implicit val format: Format[AiSuggestions] = Json.format[AiSuggestions]
  // lazy: deriving RowSuggestion's schema initializes this object, so a strict val would read RowSuggestion.schema
  // while it is still null (ExceptionInInitializerError) whenever RowSuggestion is touched first.
  implicit lazy val schema: Schema[AiSuggestions] =
    Schema.derived[AiSuggestions].name(Schema.SName("BankStatementAiSuggestions"))
}
