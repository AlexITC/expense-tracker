package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.TelegramDAO
import net.wiringbits.repositories.models.{BotUser, LinkToken}
import play.api.db.Database

import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class TelegramRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {

  def createLinkToken(token: String, userId: UUID, expiresAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TelegramDAO.createLinkToken(UUID.randomUUID(), token, userId, expiresAt)
    }
  }

  def findLinkToken(token: String): Future[Option[LinkToken]] = Future {
    database.withConnection { implicit conn =>
      TelegramDAO.findLinkToken(token)
    }
  }

  def deleteLinkToken(token: String): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TelegramDAO.deleteLinkToken(token)
    }
  }

  def createBotUser(id: UUID, userId: UUID, telegramUserId: Long): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TelegramDAO.createBotUser(id, userId, telegramUserId)
    }
  }

  def findBotUserByTelegramId(telegramUserId: Long): Future[Option[BotUser]] = Future {
    database.withConnection { implicit conn =>
      TelegramDAO.findBotUserByTelegramId(telegramUserId)
    }
  }
}
