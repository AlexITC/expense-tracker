package net.wiringbits.actions.telegram

import net.wiringbits.apis.TelegramApi
import net.wiringbits.repositories.TelegramRepository
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class TelegramLinkAction @Inject() (
    telegramRepository: TelegramRepository,
    telegramApi: TelegramApi
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(token: String, telegramUserId: Long, chatId: Long): Future[Unit] = {
    val tokenHash = hashHex(token)
    telegramRepository.findLinkToken(tokenHash).flatMap {
      case None =>
        logger.info(s"Telegram link: invalid token for telegramUserId=$telegramUserId")
        telegramApi.sendMessage(chatId, "Invalid or expired link token. Generate a new one in the web app.")
      case Some(linkToken) if linkToken.expiresAt.isBefore(clock.instant()) =>
        logger.info(s"Telegram link: expired token for telegramUserId=$telegramUserId")
        telegramApi.sendMessage(chatId, "Link token has expired. Generate a new one in the web app.")
      case Some(linkToken) =>
        for {
          _ <- telegramRepository.createBotUser(UUID.randomUUID(), linkToken.userId, telegramUserId)
          _ <- telegramRepository.deleteLinkToken(tokenHash)
          _ = logger.info(s"Telegram link: account linked userId=${linkToken.userId} telegramUserId=$telegramUserId")
          _ <- telegramApi.sendMessage(chatId, "Account linked! You can now submit expenses.")
        } yield ()
    }
  }

  private def hashHex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
}
