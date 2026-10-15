package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class TelegramReceipt(
    telegramReceiptId: UUID,
    receiptId: UUID,
    chatId: Long,
    expenseMessageId: Option[Long],
    createdAt: Instant,
    updatedAt: Instant
)
