package net.wiringbits.repositories.models

import net.wiringbits.api.models.merchants.ImportRuleAction

import java.time.Instant
import java.util.UUID

case class ImportRule(
    id: UUID,
    userId: UUID,
    action: ImportRuleAction,
    pattern: String,
    merchantName: Option[String],
    categoryName: Option[String],
    autoCreated: Boolean,
    createdAt: Instant,
    updatedAt: Instant
)

object ImportRule {
  case class Create(
      id: UUID,
      userId: UUID,
      action: ImportRuleAction,
      pattern: String,
      autoCreated: Boolean,
      createdAt: Instant,
      updatedAt: Instant
  )
}
