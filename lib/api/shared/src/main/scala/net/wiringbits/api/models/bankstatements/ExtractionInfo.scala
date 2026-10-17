package net.wiringbits.api.models.bankstatements

import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

/** How a confirmed statement was extracted: the ladder stage and model that produced it, and whether that extraction
  * reconciled.
  */
case class ExtractionInfo(
    stage: ExtractionSource,
    model: String,
    reconciled: Boolean,
    discrepancy: Option[String]
)

object ExtractionInfo {
  implicit val format: Format[ExtractionInfo] = Json.format[ExtractionInfo]
  implicit val schema: Schema[ExtractionInfo] =
    Schema.derived[ExtractionInfo].name(Schema.SName("BankStatementExtractionInfo"))
}
