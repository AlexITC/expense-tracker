package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class BotUser(
    telegramBotUserId: UUID,
    userId: UUID,
    telegramUserId: Long,
    createdAt: Instant
)
