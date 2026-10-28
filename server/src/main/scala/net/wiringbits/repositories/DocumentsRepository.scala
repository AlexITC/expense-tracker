package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.models.documents.DocumentClassification
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.daos.{BackgroundJobDAO, DocumentDAO}
import net.wiringbits.repositories.models.{BackgroundJobData, Document}
import play.api.db.Database

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class DocumentsRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext, clock: Clock) {

  // Stores the document and enqueues its processing job, or, when the same file is already stored, returns the
  // existing document's id and enqueues nothing. The id is what the caller echoes back to the uploader.
  def createIfAbsent(document: Document.Create, job: BackgroundJobData.Create): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      DocumentDAO.createIfAbsent(document) match {
        case Some(id) =>
          BackgroundJobDAO.create(job)
          id
        case None =>
          DocumentDAO
            .findBySha256(document.userId, document.sha256)
            .map(_.id)
            .getOrElse(throw new RuntimeException("Document not found after conflict"))
      }
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

  def updateClassification(id: UUID, userId: UUID, classification: DocumentClassification): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      DocumentDAO.updateClassification(id, userId, classification, clock.instant())
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      DocumentDAO.delete(id, userId)
    }
  }
}
