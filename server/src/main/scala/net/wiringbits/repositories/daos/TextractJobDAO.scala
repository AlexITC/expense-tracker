package net.wiringbits.repositories.daos

import anorm.*
import anorm.SqlParser.*
import net.wiringbits.repositories.models.TextractJob

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object TextractJobDAO {

  val textractJobParser: RowParser[TextractJob] =
    (get[UUID]("id") ~
      get[UUID]("user_id") ~
      get[UUID]("document_id") ~
      str("api_type") ~
      get[Option[String]]("job_id") ~
      get[TextractJob.Status]("status") ~
      get[Int]("attempts") ~
      get[Option[Instant]]("last_polled_at") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at")).map {
      case id ~ userId ~ documentId ~ apiType ~ jobId ~ status ~ attempts ~ lastPolledAt ~ createdAt ~ updatedAt =>
        TextractJob(id, userId, documentId, apiType, jobId, status, attempts, lastPolledAt, createdAt, updatedAt)
    }

  def create(req: TextractJob.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      INSERT INTO textract_job (id, user_id, document_id, api_type, job_id, status, attempts, created_at, updated_at)
      VALUES (
        ${req.id.toString}::UUID,
        ${req.userId.toString}::UUID,
        ${req.documentId.toString}::UUID,
        ${req.apiType},
        ${req.jobId},
        ${TextractJob.Status.entryName(req.status)},
        0,
        ${req.createdAt},
        ${req.updatedAt}
      )
      """.execute()
  }

  def findByDocumentId(documentId: UUID)(implicit conn: Connection): Option[TextractJob] =
    SQL"""
      SELECT id, user_id, document_id, api_type, job_id, status, attempts, last_polled_at, created_at, updated_at
      FROM textract_job
      WHERE document_id = ${documentId.toString}::UUID
      """.as(textractJobParser.singleOpt)

  def updateStatus(id: UUID, status: TextractJob.Status, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE textract_job
      SET status = ${TextractJob.Status.entryName(status)},
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
  }

  def incrementAttempts(id: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE textract_job
      SET attempts = attempts + 1,
          last_polled_at = $updatedAt,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
  }

  def markSucceeded(id: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE textract_job
      SET status = 'succeeded',
          last_polled_at = $updatedAt,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
  }

  def markFailed(id: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE textract_job
      SET status = 'failed',
          last_polled_at = $updatedAt,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
  }
}
