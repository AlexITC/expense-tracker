package net.wiringbits.repositories.daos

import anorm.*
import anorm.SqlParser.*
import net.wiringbits.repositories.models.BankStatementDraft

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object BankStatementDraftDAO {

  private val parser: RowParser[BankStatementDraft] =
    (get[UUID]("id") ~
      get[UUID]("user_id") ~
      get[UUID]("document_id") ~
      str("sha256") ~
      str("filename") ~
      str("storage_key") ~
      str("status") ~
      get[Option[String]]("error") ~
      get[Option[String]]("payload") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at")).map {
      case id ~ userId ~ documentId ~ sha256 ~ filename ~ storageKey ~ status ~ error ~ payload ~ createdAt ~ updatedAt =>
        BankStatementDraft(
          id,
          userId,
          documentId,
          sha256,
          filename,
          storageKey,
          status,
          error,
          payload,
          createdAt,
          updatedAt
        )
    }

  private val selectColumns =
    "id, user_id, document_id, sha256, filename, storage_key, status, error, payload::TEXT AS payload, created_at, updated_at"

  def listByUser(userId: UUID)(implicit conn: Connection): List[BankStatementDraft] =
    SQL(s"SELECT $selectColumns FROM bank_statement_draft WHERE user_id = {userId}::UUID ORDER BY created_at, id")
      .on("userId" -> userId.toString)
      .as(parser.*)

  def findByIdForUser(id: UUID, userId: UUID)(implicit conn: Connection): Option[BankStatementDraft] =
    SQL(s"SELECT $selectColumns FROM bank_statement_draft WHERE id = {id}::UUID AND user_id = {userId}::UUID")
      .on("id" -> id.toString, "userId" -> userId.toString)
      .as(parser.singleOpt)

  // Internal (no user scope): used by the extraction background job.
  def findById(id: UUID)(implicit conn: Connection): Option[BankStatementDraft] =
    SQL(s"SELECT $selectColumns FROM bank_statement_draft WHERE id = {id}::UUID")
      .on("id" -> id.toString)
      .as(parser.singleOpt)

  def create(draft: BankStatementDraft.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      INSERT INTO bank_statement_draft (
        id, user_id, document_id, sha256, filename, storage_key, status, created_at, updated_at
      )
      VALUES (
        ${draft.id.toString}::UUID,
        ${draft.userId.toString}::UUID,
        ${draft.documentId.toString}::UUID,
        ${draft.sha256},
        ${draft.filename},
        ${draft.storageKey},
        ${BankStatementDraft.Status.Processing},
        ${draft.createdAt},
        ${draft.updatedAt}
      )
      """.execute()
  }

  def markReady(id: UUID, payload: String, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE bank_statement_draft
      SET payload = $payload::JSONB,
          status = ${BankStatementDraft.Status.Ready},
          error = NULL,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
  }

  def markFailed(id: UUID, error: String, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE bank_statement_draft
      SET status = ${BankStatementDraft.Status.Failed},
          error = $error,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
  }

  def updatePayload(id: UUID, userId: UUID, payload: String, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_draft
      SET payload = $payload::JSONB,
          status = ${BankStatementDraft.Status.Ready},
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("No draft to update")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Boolean =
    SQL"DELETE FROM bank_statement_draft WHERE id = ${id.toString}::UUID AND user_id = ${userId.toString}::UUID"
      .executeUpdate() > 0
}
