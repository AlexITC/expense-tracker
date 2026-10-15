package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.SavingsGoal

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object SavingsGoalDAO {

  import anorm.*

  def create(goal: SavingsGoal.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO savings_goal (id, user_id, name, target_amount, created_at, updated_at)
        VALUES (
          ${goal.id.toString}::UUID,
          ${goal.userId.toString}::UUID,
          ${goal.name},
          ${goal.targetAmount},
          ${goal.createdAt},
          ${goal.updatedAt}
        )
        """.executeUpdate()
  }

  def all(userId: UUID)(implicit conn: Connection): List[(SavingsGoal, BigDecimal)] =
    SQL"""
        SELECT g.id, g.user_id, g.name, g.target_amount, g.created_at, g.updated_at,
               COALESCE(SUM(a.amount), 0) AS balance
        FROM savings_goal g
        LEFT JOIN savings_allocation a ON a.goal_id = g.id
        WHERE g.user_id = ${userId.toString}::UUID
        GROUP BY g.id, g.user_id, g.name, g.target_amount, g.created_at, g.updated_at
        ORDER BY g.name
        """.as(savingsGoalWithBalanceParser.*)

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[SavingsGoal] =
    SQL"""
        SELECT id, user_id, name, target_amount, created_at, updated_at
        FROM savings_goal
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(savingsGoalParser.singleOpt)

  def findByName(userId: UUID, name: String)(implicit conn: Connection): Option[SavingsGoal] =
    SQL"""
        SELECT id, user_id, name, target_amount, created_at, updated_at
        FROM savings_goal
        WHERE user_id = ${userId.toString}::UUID
          AND name = $name::CITEXT
        """.as(savingsGoalParser.singleOpt)

  def update(id: UUID, userId: UUID, name: String, targetAmount: Option[BigDecimal], updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
        UPDATE savings_goal
        SET name = $name, target_amount = $targetAmount, updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Savings goal not found")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM savings_goal
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Savings goal not found")
  }
}
