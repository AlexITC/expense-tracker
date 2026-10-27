package net.wiringbits.repositories.models

import net.wiringbits.api.models.merchants.ImportRuleAction

import java.time.Instant
import java.util.UUID

case class ImportRule(
    id: UUID,
    userId: UUID,
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

object ImportRule {
  case class Create(
      id: UUID,
      userId: UUID,
      action: ImportRuleAction,
      pattern: String,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      autoCreated: Boolean,
      createdAt: Instant,
      updatedAt: Instant
  )
}
