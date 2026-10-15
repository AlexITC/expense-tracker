package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class Merchant(
    id: UUID,
    userId: UUID,
    name: String,
    notes: Option[String],
    categoryId: Option[UUID],
    taxId: Option[String],
    reviewed: Boolean,
    createdAt: Instant,
    updatedAt: Instant
)

object Merchant {
  case class Create(
      id: UUID,
      userId: UUID,
      name: String,
      notes: Option[String],
      categoryId: Option[UUID],
      taxId: Option[String],
      reviewed: Boolean,
      createdAt: Instant,
      updatedAt: Instant
  )
}
