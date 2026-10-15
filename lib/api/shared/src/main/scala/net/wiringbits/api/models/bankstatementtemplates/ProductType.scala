package net.wiringbits.api.models.bankstatementtemplates

import play.api.libs.json.*
import sttp.tapir.Schema

/** Credit card vs bank/current account. */
sealed abstract class ProductType(val entryName: String)

object ProductType {
  case object Credit extends ProductType("credit")
  case object Debit extends ProductType("debit")

  val values: Seq[ProductType] = Seq(Credit, Debit)

  def fromEntryName(name: String): ProductType =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown ProductType: $name"))

  implicit val format: Format[ProductType] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[ProductType]](JsError(s"Unknown ProductType: $s"))(JsSuccess(_))
    }),
    Writes(a => JsString(a.entryName))
  )

  implicit val schema: Schema[ProductType] = Schema.string[ProductType]
}
