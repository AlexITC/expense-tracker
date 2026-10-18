package net.wiringbits.api.models.bankstatementtemplates

import play.api.libs.json.*
import sttp.tapir.Schema

/** Which extraction-ladder stage produced a statement's transactions. */
sealed abstract class ExtractionSource(val entryName: String)

object ExtractionSource {
  case object PdfText extends ExtractionSource("pdf_text")
  case object BdaMarkdown extends ExtractionSource("bda_markdown")
  case object TextractTables extends ExtractionSource("textract_tables")
  case object BdaCustom extends ExtractionSource("bda_custom")

  val values: Seq[ExtractionSource] = Seq(PdfText, BdaMarkdown, TextractTables, BdaCustom)

  def fromEntryName(name: String): ExtractionSource =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown ExtractionSource: $name"))

  implicit val format: Format[ExtractionSource] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[ExtractionSource]](JsError(s"Unknown ExtractionSource: $s"))(JsSuccess(_))
    }),
    Writes(a => JsString(a.entryName))
  )

  implicit val schema: Schema[ExtractionSource] = Schema.string[ExtractionSource]
}
