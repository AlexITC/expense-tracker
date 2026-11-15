package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.SubscriptionExtraCharge

import java.sql.Connection
import java.util.UUID

object SubscriptionExtraChargeDAO {

  import anorm.*

  def create(charge: SubscriptionExtraCharge)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO subscription_extra_charge (
          id, user_id, subscription_id, label, amount, recurrence, due_date, created_at, updated_at
        )
        VALUES (
          ${charge.id.toString}::UUID,
          ${charge.userId.toString}::UUID,
          ${charge.subscriptionId.toString}::UUID,
          ${charge.label},
          ${charge.amount},
          ${charge.recurrence.entryName},
          ${charge.dueDate},
          ${charge.createdAt},
          ${charge.updatedAt}
        )
        """.executeUpdate()
  }

  def countForSubscription(subscriptionId: UUID, userId: UUID)(implicit conn: Connection): Long =
    SQL"""
        SELECT COUNT(*)
        FROM subscription_extra_charge
        WHERE subscription_id = ${subscriptionId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(SqlParser.scalar[Long].single)

  def allForUser(userId: UUID)(implicit conn: Connection): List[SubscriptionExtraCharge] =
    SQL"""
        SELECT id, user_id, subscription_id, label, amount, recurrence, due_date, created_at, updated_at
        FROM subscription_extra_charge
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY subscription_id, due_date, id
        """.as(subscriptionExtraChargeParser.*)

  def delete(id: UUID, subscriptionId: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM subscription_extra_charge
        WHERE id = ${id.toString}::UUID
          AND subscription_id = ${subscriptionId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Extra charge not found")
  }
}
