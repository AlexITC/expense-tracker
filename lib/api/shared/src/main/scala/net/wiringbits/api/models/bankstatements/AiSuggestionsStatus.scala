package net.wiringbits.api.models.bankstatements

import play.api.libs.json.*
import sttp.tapir.Schema

/** Lifecycle of the AI category/merchant suggestions for a confirmed statement. */
sealed abstract class AiSuggestionsStatus(val entryName: String)

object AiSuggestionsStatus {
  case object Pending extends AiSuggestionsStatus("Pending")
  case object Done extends AiSuggestionsStatus("Done")
  case object Failed extends AiSuggestionsStatus("Failed")

  val values: Seq[AiSuggestionsStatus] = Seq(Pending, Done, Failed)

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
