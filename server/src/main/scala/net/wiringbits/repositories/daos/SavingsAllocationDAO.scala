package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.SavingsAllocation

import java.sql.Connection
import java.time.{Instant, LocalDate}
import java.util.UUID

object SavingsAllocationDAO {

  import anorm.*

  def create(allocation: SavingsAllocation.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO savings_allocation (id, user_id, goal_id, amount, date, note, merchant_id, created_at, updated_at)
        VALUES (
          ${allocation.id.toString}::UUID,
          ${allocation.userId.toString}::UUID,
          ${allocation.goalId.toString}::UUID,
          ${allocation.amount},
          ${allocation.date},
          ${allocation.note},
          ${allocation.merchantId.map(_.toString)}::UUID,
          ${allocation.createdAt},
          ${allocation.updatedAt}
        )
        """.executeUpdate()
  }

  def all(goalId: UUID, userId: UUID)(implicit conn: Connection): List[SavingsAllocation] =
    SQL"""
        SELECT id, user_id, goal_id, amount, date, note, merchant_id, created_at, updated_at
        FROM savings_allocation
        WHERE goal_id = ${goalId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        ORDER BY date DESC, id
        """.as(savingsAllocationParser.*)

  def find(id: UUID, goalId: UUID, userId: UUID)(implicit conn: Connection): Option[SavingsAllocation] =
    SQL"""
        SELECT id, user_id, goal_id, amount, date, note, merchant_id, created_at, updated_at
        FROM savings_allocation
        WHERE id = ${id.toString}::UUID
          AND goal_id = ${goalId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(savingsAllocationParser.singleOpt)

  def update(
      id: UUID,
      goalId: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      note: Option[String],
      merchantId: Option[UUID],
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE savings_allocation
        SET amount = $amount, date = $date, note = $note,
            merchant_id = ${merchantId.map(_.toString)}::UUID, updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND goal_id = ${goalId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Savings allocation not found")
  }

  def delete(id: UUID, goalId: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM savings_allocation
        WHERE id = ${id.toString}::UUID
          AND goal_id = ${goalId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Savings allocation not found")
  }
}
