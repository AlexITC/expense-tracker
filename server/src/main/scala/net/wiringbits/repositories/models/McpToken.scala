package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class McpToken(
    id: UUID,
    userId: UUID,
    tokenHash: String,
    createdAt: Instant,
    lastUsedAt: Option[Instant]
)
