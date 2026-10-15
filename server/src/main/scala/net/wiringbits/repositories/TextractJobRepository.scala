package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.TextractJobDAO
import net.wiringbits.repositories.models.TextractJob
import play.api.db.Database

import java.time.{Clock, Instant}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class TextractJobRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext, clock: Clock) {

  def create(job: TextractJob.Create): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TextractJobDAO.create(job)
    }
  }

  def findByDocumentId(documentId: UUID): Future[Option[TextractJob]] = Future {
    database.withConnection { implicit conn =>
      TextractJobDAO.findByDocumentId(documentId)
    }
  }

  def incrementAttempts(id: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TextractJobDAO.incrementAttempts(id, clock.instant())
    }
  }

  def markSucceeded(id: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TextractJobDAO.markSucceeded(id, clock.instant())
    }
  }

  def markFailed(id: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TextractJobDAO.markFailed(id, clock.instant())
    }
  }
}
