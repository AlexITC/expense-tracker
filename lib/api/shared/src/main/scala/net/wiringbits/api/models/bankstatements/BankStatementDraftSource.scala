package net.wiringbits.api.models.bankstatements

import play.api.libs.json.*
import sttp.tapir.Schema

/** The kind of file a bank-statement draft was created from. */
enum BankStatementDraftSource(val entryName: String) {
  case Pdf extends BankStatementDraftSource("Pdf")
  case Csv extends BankStatementDraftSource("Csv")
}

object BankStatementDraftSource {
  implicit val format: Format[BankStatementDraftSource] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[BankStatementDraftSource]](JsError(s"Unknown BankStatementDraftSource: $s"))(JsSuccess(_))
    }),
    Writes(s => JsString(s.entryName))
  )

  implicit val schema: Schema[BankStatementDraftSource] = Schema.string[BankStatementDraftSource]
}
