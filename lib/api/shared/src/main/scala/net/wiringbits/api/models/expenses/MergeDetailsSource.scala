package net.wiringbits.api.models.expenses

import play.api.libs.json.*
import sttp.tapir.Schema

/** Which side of a duplicate merge supplies the surviving expense's merchant, notes and category splits. */
enum MergeDetailsSource(val entryName: String) {
  case Row extends MergeDetailsSource("row")
  case Receipt extends MergeDetailsSource("receipt")
}

object MergeDetailsSource {
  implicit val format: Format[MergeDetailsSource] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[MergeDetailsSource]](JsError(s"Unknown MergeDetailsSource: $s"))(JsSuccess(_))
    }),
    Writes(k => JsString(k.entryName))
  )

  implicit val schema: Schema[MergeDetailsSource] = Schema.string[MergeDetailsSource]
}
