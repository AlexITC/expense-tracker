package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.SubscriptionManualPayment

import java.sql.Connection
import java.util.UUID

object SubscriptionManualPaymentDAO {

  import anorm.*

  def create(payment: SubscriptionManualPayment)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO subscription_manual_payment (
          id, user_id, subscription_id, paid_date, amount, note, created_at, updated_at
        )
        VALUES (
          ${payment.id.toString}::UUID,
          ${payment.userId.toString}::UUID,
          ${payment.subscriptionId.toString}::UUID,
          ${payment.paidDate},
          ${payment.amount},
          ${payment.note.map(_.string)},
          ${payment.createdAt},
          ${payment.updatedAt}
        )
        """.executeUpdate()
  }

  def countForSubscription(subscriptionId: UUID, userId: UUID)(implicit conn: Connection): Long =
    SQL"""
        SELECT COUNT(*)
        FROM subscription_manual_payment
        WHERE subscription_id = ${subscriptionId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(SqlParser.scalar[Long].single)

  def allForUser(userId: UUID)(implicit conn: Connection): List[SubscriptionManualPayment] =
    SQL"""
        SELECT id, user_id, subscription_id, paid_date, amount, note, created_at, updated_at
        FROM subscription_manual_payment
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY subscription_id, paid_date, id
        """.as(subscriptionManualPaymentParser.*)

  def delete(id: UUID, subscriptionId: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM subscription_manual_payment
        WHERE id = ${id.toString}::UUID
          AND subscription_id = ${subscriptionId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Manual payment not found")
  }
}
