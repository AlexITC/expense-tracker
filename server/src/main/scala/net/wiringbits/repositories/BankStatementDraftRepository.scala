package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{BackgroundJobDAO, BankStatementDraftDAO}
import net.wiringbits.repositories.models.{BackgroundJobData, BankStatementDraft}
import play.api.db.Database

import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class BankStatementDraftRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {

  def listByUser(userId: UUID): Future[List[BankStatementDraft]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.listByUser(userId)
    }
  }

  def findByIdForUser(id: UUID, userId: UUID): Future[Option[BankStatementDraft]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.findByIdForUser(id, userId)
    }
  }

  def findById(id: UUID): Future[Option[BankStatementDraft]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.findById(id)
    }
  }

  /** Create the draft (status=processing) and enqueue its extraction job in one transaction. */
  def create(draft: BankStatementDraft.Create, job: BackgroundJobData.Create): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      BankStatementDraftDAO.create(draft)
      BackgroundJobDAO.create(job)
    }
  }

  def markReady(id: UUID, payload: String, updatedAt: Instant): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.markReady(id, payload, updatedAt)
    }
  }

  def markFailed(id: UUID, error: String, updatedAt: Instant): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.markFailed(id, error, updatedAt)
    }
  }

  def updatePayload(id: UUID, userId: UUID, payload: String, updatedAt: Instant): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.updatePayload(id, userId, payload, updatedAt)
    }
  }

  def delete(id: UUID, userId: UUID): Future[Boolean] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.delete(id, userId)
    }
  }
}
