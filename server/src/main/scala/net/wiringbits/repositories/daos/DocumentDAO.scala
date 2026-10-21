package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.Document

import java.sql.Connection
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

  def all(userId: UUID)(implicit conn: Connection): List[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at
        FROM document
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY created_at DESC, id
        """.as(documentParser.*)
  }

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at
        FROM document
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(documentParser.singleOpt)
  }

  def findById(id: UUID)(implicit conn: Connection): Option[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at
        FROM document
        WHERE id = ${id.toString}::UUID
        """.as(documentParser.singleOpt)
  }

  def findBySha256(userId: UUID, sha256: String)(implicit conn: Connection): Option[Document] = {
    SQL"""
        SELECT id, user_id, sha256, storage_key, kind, filename, created_at, updated_at
        FROM document
        WHERE user_id = ${userId.toString}::UUID
          AND sha256 = $sha256
        """.as(documentParser.singleOpt)
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
