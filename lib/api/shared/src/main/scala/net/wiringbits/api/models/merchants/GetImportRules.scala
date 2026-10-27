package net.wiringbits.api.models.merchants

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.Instant
import java.util.UUID

object GetImportRules {
  case class Rule(
      id: UUID,
      action: ImportRuleAction,
      pattern: String,
      merchantId: Option[UUID],
      merchantName: Option[String],
      categoryId: Option[UUID],
      categoryName: Option[String],
      autoCreated: Boolean,
      createdAt: Instant,
      updatedAt: Instant
  )
  case class Response(data: List[Rule])

  object Rule {
    implicit val format: Format[Rule] = Json.format[Rule]
    implicit val schema: Schema[Rule] = Schema.derived[Rule].name(Schema.SName("GetImportRulesRule"))
  }

  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetImportRulesResponse"))
}
