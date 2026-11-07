package net.wiringbits.api.models.bankstatements

import play.api.libs.json.*
import sttp.tapir.Schema

/** Kind of non-blocking issue detected when confirming a bank statement draft. */
sealed abstract class ConfirmWarningKind(val entryName: String)

object ConfirmWarningKind {
  case object Overlap extends ConfirmWarningKind("Overlap")
  case object PeriodLength extends ConfirmWarningKind("PeriodLength")
  case object PeriodOrder extends ConfirmWarningKind("PeriodOrder")
  case object FuturePeriod extends ConfirmWarningKind("FuturePeriod")

  val values: Seq[ConfirmWarningKind] = Seq(Overlap, PeriodLength, PeriodOrder, FuturePeriod)

  implicit val format: Format[ConfirmWarningKind] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[ConfirmWarningKind]](JsError(s"Unknown ConfirmWarningKind: $s"))(JsSuccess(_))
    }),
    Writes(a => JsString(a.entryName))
  )

  implicit val schema: Schema[ConfirmWarningKind] = Schema.string[ConfirmWarningKind]
}

case class ConfirmWarning(kind: ConfirmWarningKind, message: String)

object ConfirmWarning {
  implicit val format: Format[ConfirmWarning] = Json.format[ConfirmWarning]
  implicit val schema: Schema[ConfirmWarning] =
    Schema.derived[ConfirmWarning].name(Schema.SName("BankStatementConfirmWarning"))
}
