package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.Merchant

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object MerchantDAO {

  import anorm.*

  def create(request: Merchant.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO merchant (id, user_id, name, notes, category_id, tax_id, reviewed, created_at, updated_at)
        VALUES (
          ${request.id.toString}::UUID,
          ${request.userId.toString}::UUID,
          ${request.name},
          ${request.notes},
          ${request.categoryId.map(_.toString)}::UUID,
          ${request.taxId},
          ${request.reviewed},
          ${request.createdAt},
          ${request.updatedAt}
        )
        """.executeUpdate()
  }

  def count(userId: UUID)(implicit conn: Connection): Long = {
    SQL"""
        SELECT COUNT(*)
        FROM merchant
        WHERE user_id = ${userId.toString}::UUID
        """.as(anorm.SqlParser.scalar[Long].single)
  }

  def all(userId: UUID)(implicit conn: Connection): List[Merchant] = {
    SQL"""
        SELECT id, user_id, name, notes, category_id, tax_id, reviewed, created_at, updated_at
        FROM merchant
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY name
        """.as(merchantParser.*)
  }

  def findPending(userId: UUID)(implicit conn: Connection): List[Merchant] = {
    SQL"""
        SELECT id, user_id, name, notes, category_id, tax_id, reviewed, created_at, updated_at
        FROM merchant
        WHERE user_id = ${userId.toString}::UUID
          AND reviewed = FALSE
        ORDER BY name
        """.as(merchantParser.*)
  }

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Merchant] = {
    SQL"""
        SELECT id, user_id, name, notes, category_id, tax_id, reviewed, created_at, updated_at
        FROM merchant
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(merchantParser.singleOpt)
  }

  def findByName(userId: UUID, name: String)(implicit conn: Connection): Option[Merchant] = {
    SQL"""
        SELECT id, user_id, name, notes, category_id, tax_id, reviewed, created_at, updated_at
        FROM merchant
        WHERE user_id = ${userId.toString}::UUID
          AND name = $name::CITEXT
        """.as(merchantParser.singleOpt)
  }

  def findByTaxId(userId: UUID, taxId: String)(implicit conn: Connection): Option[Merchant] = {
    SQL"""
        SELECT id, user_id, name, notes, category_id, tax_id, reviewed, created_at, updated_at
        FROM merchant
        WHERE user_id = ${userId.toString}::UUID
          AND tax_id = $taxId
        """.as(merchantParser.singleOpt)
  }

  def update(
      id: UUID,
      userId: UUID,
      name: String,
      notes: Option[String],
      categoryId: Option[UUID],
      taxId: Option[String],
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE merchant
        SET name = $name, notes = $notes, category_id = ${categoryId.map(_.toString)}::UUID,
            tax_id = $taxId, reviewed = TRUE, updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Merchant not found")
  }

  def markReviewed(id: UUID, userId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE merchant
        SET reviewed = TRUE, updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Merchant not found")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM merchant
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Merchant not found")
  }

  def findAndDeletePending(pendingId: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    SQL"""
        SELECT id
        FROM merchant
        WHERE id = ${pendingId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
          AND reviewed = FALSE
        """
      .as(anorm.SqlParser.scalar[UUID].singleOpt)
      .getOrElse(throw new RuntimeException("Pending merchant not found"))

    val deleted = SQL"""
        DELETE FROM merchant
        WHERE id = ${pendingId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Pending merchant not found")
  }
}
