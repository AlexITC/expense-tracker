package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{BackgroundJobDAO, BankStatementDraftDAO, DocumentDAO}
import net.wiringbits.repositories.models.{BackgroundJobData, BankStatementDraft}
import net.wiringbits.repositories.support.ConstraintViolations
import play.api.db.Database

import java.sql.Connection
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
      BankStatementDraftDAO.findByIdForUser(id = id, userId = userId)
    }
  }

  def findById(id: UUID): Future[Option[BankStatementDraft]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.findById(id)
    }
  }

  /** Create the draft (status=processing) and enqueue its extraction job in one transaction, inserting its document
    * first when the link is a new one.
    */
  def create(
      documentLink: BankStatementDraft.DocumentLink,
      draft: BankStatementDraft.Create,
      job: BackgroundJobData.Create
  ): Future[Unit] = Future {
    ConstraintViolations.translating(DocumentSha256Unique) {
      database.withTransaction { implicit conn =>
        linkDocument(documentLink, draft.userId)
        BankStatementDraftDAO.create(draft)
        BackgroundJobDAO.create(job)
      }
    }
  }

  /** Create an already-ready draft with its payload (no extraction job), inserting its document first when the link is
    * a new one.
    */
  def createReady(
      documentLink: BankStatementDraft.DocumentLink,
      draft: BankStatementDraft.Create,
      payload: String
  ): Future[Unit] = Future {
    ConstraintViolations.translating(DocumentSha256Unique) {
      database.withTransaction { implicit conn =>
        linkDocument(documentLink, draft.userId)
        BankStatementDraftDAO.insert(draft, BankStatementDraft.Status.Ready, payload = Some(payload))
      }
    }
  }

  // A concurrent upload of the same file can insert the document first; the loser surfaces as a queue duplicate.
  private val DocumentSha256Unique: (String, String) =
    "document_user_sha256_unique" -> "This file is already in your import queue"

  /** Insert a new document, or verify an existing one belongs to the draft's user, so a draft never links another
    * user's document.
    */
  private def linkDocument(documentLink: BankStatementDraft.DocumentLink, userId: UUID)(implicit
      conn: Connection
  ): Unit = documentLink match {
    case BankStatementDraft.DocumentLink.New(document) => DocumentDAO.create(document)
    case BankStatementDraft.DocumentLink.Existing(id) =>
      if (DocumentDAO.find(id = id, userId = userId).isEmpty) throw new RuntimeException("Document not found")
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
      BankStatementDraftDAO.updatePayload(id = id, userId = userId, payload, updatedAt)
    }
  }

  def delete(id: UUID, userId: UUID): Future[Boolean] = Future {
    database.withConnection { implicit conn =>
      BankStatementDraftDAO.delete(id = id, userId = userId)
    }
  }
}
