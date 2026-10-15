package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class LinkToken(
    telegramLinkTokenId: UUID,
    token: String,
    userId: UUID,
    expiresAt: Instant,
    createdAt: Instant
)
