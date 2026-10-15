package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.{BotUser, LinkToken, TelegramReceipt}

import anorm.SqlStringInterpolation
import java.sql.Connection
import java.time.Instant
import java.util.UUID

object TelegramDAO {

  def createLinkToken(id: UUID, token: String, userId: UUID, expiresAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO telegram_link_token (telegram_link_token_id, token, user_id, expires_at)
        VALUES (
          ${id.toString}::UUID,
          $token,
          ${userId.toString}::UUID,
          $expiresAt
        )
        """.execute()
  }

  def findLinkToken(token: String)(implicit conn: Connection): Option[LinkToken] = {
    SQL"""
        SELECT telegram_link_token_id, token, user_id, expires_at, created_at
        FROM telegram_link_token
        WHERE token = $token
        """.as(linkTokenParser.singleOpt)
  }

  def deleteLinkToken(token: String)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        DELETE FROM telegram_link_token
        WHERE token = $token
        """.executeUpdate()
  }

  def createBotUser(id: UUID, userId: UUID, telegramUserId: Long)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO telegram_bot_user (telegram_bot_user_id, user_id, telegram_user_id)
        VALUES (
          ${id.toString}::UUID,
          ${userId.toString}::UUID,
          $telegramUserId
        )
        """.execute()
  }

  def findBotUserByTelegramId(telegramUserId: Long)(implicit conn: Connection): Option[BotUser] = {
    SQL"""
        SELECT telegram_bot_user_id, user_id, telegram_user_id, created_at
        FROM telegram_bot_user
        WHERE telegram_user_id = $telegramUserId
        """.as(botUserParser.singleOpt)
  }

  def createReceipt(id: UUID, receiptId: UUID, chatId: Long)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO telegram_receipt (telegram_receipt_id, receipt_id, chat_id)
        VALUES (
          ${id.toString}::UUID,
          ${receiptId.toString}::UUID,
          $chatId
        )
        """.execute()
  }

  def findReceiptByReceiptId(receiptId: UUID)(implicit conn: Connection): Option[TelegramReceipt] = {
    SQL"""
        SELECT telegram_receipt_id, receipt_id, chat_id, expense_message_id, created_at, updated_at
        FROM telegram_receipt
        WHERE receipt_id = ${receiptId.toString}::UUID
        """.as(telegramReceiptParser.singleOpt)
  }

  def findReceiptByExpenseId(expenseId: UUID)(implicit conn: Connection): Option[TelegramReceipt] = {
    SQL"""
        SELECT tr.telegram_receipt_id, tr.receipt_id, tr.chat_id, tr.expense_message_id, tr.created_at, tr.updated_at
        FROM telegram_receipt tr
        JOIN receipt r ON tr.receipt_id = r.id
        WHERE r.expense_id = ${expenseId.toString}::UUID
        """.as(telegramReceiptParser.singleOpt)
  }

  def updateReceiptExpenseMessageId(id: UUID, messageId: Long)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        UPDATE telegram_receipt
        SET expense_message_id = $messageId, updated_at = NOW()
        WHERE telegram_receipt_id = ${id.toString}::UUID
        """.executeUpdate()
  }
}
