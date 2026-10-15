package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class Category(id: UUID, userId: UUID, name: String, createdAt: Instant, updatedAt: Instant)

object Category {
  case class Create(id: UUID, userId: UUID, name: String, createdAt: Instant, updatedAt: Instant)
}
