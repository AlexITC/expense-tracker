package net.wiringbits.api.models.bankstatements

import play.api.libs.json.*
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

/** Lifecycle of the AI category/merchant suggestions for a confirmed statement. */
sealed abstract class AiSuggestionsStatus(val entryName: String)

object AiSuggestionsStatus {
  case object Pending extends AiSuggestionsStatus("Pending")
  case object Done extends AiSuggestionsStatus("Done")
  case object Failed extends AiSuggestionsStatus("Failed")

  val values: Seq[AiSuggestionsStatus] = Seq(Pending, Done, Failed)

  def fromEntryName(name: String): AiSuggestionsStatus =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown AiSuggestionsStatus: $name"))

  implicit val format: Format[AiSuggestionsStatus] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[AiSuggestionsStatus]](JsError(s"Unknown AiSuggestionsStatus: $s"))(JsSuccess(_))
    }),
    Writes(a => JsString(a.entryName))
  )

  implicit val schema: Schema[AiSuggestionsStatus] = Schema.string[AiSuggestionsStatus]
}

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
  implicit val schema: Schema[AiSuggestions] =
    Schema.derived[AiSuggestions].name(Schema.SName("BankStatementAiSuggestions"))
}
