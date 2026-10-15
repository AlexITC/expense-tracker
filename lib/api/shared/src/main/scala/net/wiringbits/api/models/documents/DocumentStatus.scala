package net.wiringbits.api.models.documents

import play.api.libs.json.*
import sttp.tapir.Schema

sealed abstract class DocumentStatus(val entryName: String)

object DocumentStatus {
  case object Ingested extends DocumentStatus("INGESTED")
  case object Processing extends DocumentStatus("PROCESSING")
  case object Analyzed extends DocumentStatus("ANALYZED")
  case object Parsed extends DocumentStatus("PARSED")
  case object ParseFailed extends DocumentStatus("PARSEFAILED")
  case object Unsupported extends DocumentStatus("UNSUPPORTED")

  val values: Seq[DocumentStatus] = Seq(Ingested, Processing, Analyzed, Parsed, ParseFailed, Unsupported)

  def fromEntryName(name: String): DocumentStatus =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown DocumentStatus: $name"))

  implicit val format: Format[DocumentStatus] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[DocumentStatus]](JsError(s"Unknown status: $s"))(JsSuccess(_))
    }),
    Writes(s => JsString(s.entryName))
  )

  implicit val schema: Schema[DocumentStatus] = Schema.string[DocumentStatus]
}
