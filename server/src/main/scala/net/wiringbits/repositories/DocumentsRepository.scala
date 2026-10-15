package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.daos.{BackgroundJobDAO, DocumentDAO}
import net.wiringbits.repositories.models.{BackgroundJobData, Document}
import play.api.db.Database

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class DocumentsRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext, clock: Clock) {

  def create(document: Document.Create, job: BackgroundJobData.Create): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      DocumentDAO.create(document)
      BackgroundJobDAO.create(job)
    }
  }

  def all(userId: UUID): Future[List[Document]] = Future {
    database.withConnection { implicit conn =>
      DocumentDAO.all(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Document]] = Future {
    database.withConnection { implicit conn =>
      DocumentDAO.find(id, userId)
    }
  }

  def findById(id: UUID): Future[Option[Document]] = Future {
    database.withConnection { implicit conn =>
      DocumentDAO.findById(id)
    }
  }

  def findBySha256(userId: UUID, sha256: String): Future[Option[Document]] = Future {
    database.withConnection { implicit conn =>
      DocumentDAO.findBySha256(userId, sha256)
    }
  }

  def updateStatus(
      id: UUID,
      status: DocumentStatus,
      parserVersion: Option[String]
  ): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      DocumentDAO.updateStatus(id, status, parserVersion, clock.instant())
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      DocumentDAO.delete(id, userId)
    }
  }
}
