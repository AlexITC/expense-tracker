package net.wiringbits.api.models.expenses

import play.api.libs.json.*
import sttp.tapir.Schema

/** The duplicate shape the client detected, so the server can refuse a merge whose expenses changed since detection.
  *   - Receipt: the loser has a receipt and the survivor has none; the receipt moves to the survivor.
  *   - Orphan: the loser has no receipt.
  *   - ReceiptOnBoth: both have a receipt; the loser's receipt and its document are discarded.
  */
enum MergeShape(val entryName: String) {
  case Receipt extends MergeShape("receipt")
  case Orphan extends MergeShape("orphan")
  case ReceiptOnBoth extends MergeShape("receipt_on_both")
}

object MergeShape {
  implicit val format: Format[MergeShape] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[MergeShape]](JsError(s"Unknown MergeShape: $s"))(JsSuccess(_))
    }),
    Writes(k => JsString(k.entryName))
  )

  implicit val schema: Schema[MergeShape] = Schema.string[MergeShape]
}
