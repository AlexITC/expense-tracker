package net.wiringbits.repositories.daos

import net.wiringbits.models.documents.DocumentClassification
import net.wiringbits.repositories.models.Document

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object DocumentDAO {

  import anorm.*

  def create(request: Document.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO document (id, user_id, sha256, storage_key, kind, filename, created_at, updated_at)
        VALUES (
          ${request.id.toString}::UUID,
          ${request.userId.toString}::UUID,
          ${request.sha256},
          ${request.storageKey},
          ${request.kind.entryName},
          ${request.filename},
          ${request.createdAt},
          ${request.updatedAt}
        )
        """.executeUpdate()
  }

  def createIfAbsent(request: Document.Create)(implicit conn: Connection): Option[UUID] = {
    SQL"""
        INSERT INTO document (id, user_id, sha256, storage_key, kind, filename, created_at, updated_at)
        VALUES (
          ${request.id.toString}::UUID,
          ${request.userId.toString}::UUID,
          ${request.sha256},
          ${request.storageKey},
          ${request.kind.entryName},
          ${request.filename},
          ${request.createdAt},
          ${request.updatedAt}
        )
        ON CONFLICT (user_id, sha256) DO NOTHING
        RETURNING id
        """.as(anorm.SqlParser.scalar[UUID].singleOpt)
  }

  def all(userId: UUID)(implicit conn: Connection): List[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at,
          ai_classification, ai_classification_confidence, ai_injection_suspected, ai_injection_confidence
        FROM document
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY created_at DESC, id
        """.as(documentParser.*)
  }

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at,
          ai_classification, ai_classification_confidence, ai_injection_suspected, ai_injection_confidence
        FROM document
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(documentParser.singleOpt)
  }

  def findById(id: UUID)(implicit conn: Connection): Option[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at,
          ai_classification, ai_classification_confidence, ai_injection_suspected, ai_injection_confidence
        FROM document
        WHERE id = ${id.toString}::UUID
        """.as(documentParser.singleOpt)
  }

  def findBySha256(userId: UUID, sha256: String)(implicit conn: Connection): Option[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at,
          ai_classification, ai_classification_confidence, ai_injection_suspected, ai_injection_confidence
        FROM document
        WHERE user_id = ${userId.toString}::UUID
          AND sha256 = $sha256
        """.as(documentParser.singleOpt)
  }

  def updateClassification(
      id: UUID,
      userId: UUID,
      classification: DocumentClassification,
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE document
        SET ai_classification = ${classification.documentClass.entryName},
            ai_classification_confidence = ${classification.confidence},
            ai_injection_suspected = ${classification.injectionSuspected},
            ai_injection_confidence = ${classification.injectionConfidence},
            updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Document not found")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM document
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Document not found")
  }
}
