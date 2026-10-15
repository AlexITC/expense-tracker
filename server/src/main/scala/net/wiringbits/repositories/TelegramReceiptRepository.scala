package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.TelegramDAO
import net.wiringbits.repositories.models.TelegramReceipt
import play.api.db.Database

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class TelegramReceiptRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {

  def create(id: UUID, receiptId: UUID, chatId: Long): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TelegramDAO.createReceipt(id, receiptId, chatId)
    }
  }

  def findByReceiptId(receiptId: UUID): Future[Option[TelegramReceipt]] = Future {
    database.withConnection { implicit conn =>
      TelegramDAO.findReceiptByReceiptId(receiptId)
    }
  }

  def findByExpenseId(expenseId: UUID): Future[Option[TelegramReceipt]] = Future {
    database.withConnection { implicit conn =>
      TelegramDAO.findReceiptByExpenseId(expenseId)
    }
  }

  def updateExpenseMessageId(id: UUID, messageId: Long): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TelegramDAO.updateReceiptExpenseMessageId(id, messageId)
    }
  }
}
