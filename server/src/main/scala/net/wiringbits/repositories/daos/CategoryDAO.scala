package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.Category

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object CategoryDAO {

  import anorm.*

  def getOrCreate(request: Category.Create)(implicit conn: Connection): Category = {
    val inserted = SQL"""
        INSERT INTO category (id, user_id, name, created_at, updated_at)
        VALUES (
          ${request.id.toString}::UUID,
          ${request.userId.toString}::UUID,
          ${request.name},
          ${request.createdAt},
          ${request.updatedAt}
        )
        ON CONFLICT (user_id, name) DO NOTHING
        RETURNING id, user_id, name, created_at, updated_at
        """.as(categoryParser.singleOpt)
    inserted.getOrElse(
      findByName(request.userId, request.name)
        .getOrElse(throw new RuntimeException("Category not found after conflict"))
    )
  }

  def all(userId: UUID)(implicit conn: Connection): List[Category] = {
    SQL"""
        SELECT id, user_id, name, created_at, updated_at
        FROM category
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY name
        """.as(categoryParser.*)
  }

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Category] = {
    SQL"""
        SELECT id, user_id, name, created_at, updated_at
        FROM category
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(categoryParser.singleOpt)
  }

  def findByName(userId: UUID, name: String)(implicit conn: Connection): Option[Category] = {
    SQL"""
        SELECT id, user_id, name, created_at, updated_at
        FROM category
        WHERE user_id = ${userId.toString}::UUID
          AND name = $name::CITEXT
        """.as(categoryParser.singleOpt)
  }

  def update(id: UUID, userId: UUID, name: String, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE category
        SET name = $name, updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Category not found")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM category
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Category not found")
  }
}
