package net.wiringbits.repositories.daos

import anorm.*
import anorm.SqlParser.*
import net.wiringbits.repositories.models.Refund

import java.sql.Connection
import java.time.{Instant, LocalDate}
import java.util.UUID

object RefundDAO {

  def create(refund: Refund.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      INSERT INTO refund (id, user_id, amount, date, merchant_id, category_id, expense_id, notes, created_at, updated_at)
      VALUES (
        ${refund.id.toString}::UUID,
        ${refund.userId.toString}::UUID,
        ${refund.amount},
        ${refund.date},
        ${refund.merchantId.map(_.toString)}::UUID,
        ${refund.categoryId.map(_.toString)}::UUID,
        ${refund.expenseId.map(_.toString)}::UUID,
        ${refund.notes},
        ${refund.createdAt},
        ${refund.updatedAt}
      )
      """.executeUpdate()
  }

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Refund] =
    SQL"""
      SELECT id, user_id, amount, date, merchant_id, category_id, expense_id, notes, created_at, updated_at
      FROM refund
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.as(refundParser.singleOpt)

  def all(userId: UUID)(implicit conn: Connection): List[Refund] =
    SQL"""
      SELECT id, user_id, amount, date, merchant_id, category_id, expense_id, notes, created_at, updated_at
      FROM refund
      WHERE user_id = ${userId.toString}::UUID
      ORDER BY date DESC, id
      """.as(refundParser.*)

  def update(
      id: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      notes: Option[String],
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE refund
      SET amount = $amount,
          date = $date,
          merchant_id = ${merchantId.map(_.toString)}::UUID,
          category_id = ${categoryId.map(_.toString)}::UUID,
          notes = $notes,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Refund not found")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
      DELETE FROM refund
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Refund not found")
  }

  def setExpenseId(id: UUID, userId: UUID, expenseId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE refund
      SET expense_id = ${expenseId.toString}::UUID,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
        AND expense_id IS NULL
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Refund not found or already linked to an expense")
  }

  def updateMerchantId(pendingId: UUID, realId: UUID, userId: UUID, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    SQL"""
      UPDATE refund
      SET merchant_id = ${realId.toString}::UUID, updated_at = $updatedAt
      WHERE merchant_id = ${pendingId.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    ()
  }

  def clearExpenseId(id: UUID, userId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE refund
      SET expense_id = NULL,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Refund not found")
  }
}
