package net.wiringbits.repositories.daos

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.repositories.models.Subscription

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object SubscriptionDAO {

  import anorm.*

  def create(subscription: Subscription.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO subscription (
          id, user_id, name, cadence, interval_days, payment_method, category_id, created_at, updated_at
        )
        VALUES (
          ${subscription.id.toString}::UUID,
          ${subscription.userId.toString}::UUID,
          ${subscription.name},
          ${subscription.cadence.entryName},
          ${subscription.intervalDays},
          ${subscription.paymentMethod.entryName},
          ${subscription.categoryId.map(_.toString)}::UUID,
          ${subscription.createdAt},
          ${subscription.updatedAt}
        )
        """.executeUpdate()
  }

  def all(userId: UUID)(implicit conn: Connection): List[Subscription] =
    SQL"""
        SELECT id, user_id, name, cadence, interval_days, payment_method, category_id, created_at, updated_at
        FROM subscription
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY name
        """.as(subscriptionParser.*)

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Subscription] =
    SQL"""
        SELECT id, user_id, name, cadence, interval_days, payment_method, category_id, created_at, updated_at
        FROM subscription
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(subscriptionParser.singleOpt)

  // Row lock that serialises term changes on one subscription until the surrounding transaction ends.
  def lockForUpdate(id: UUID, userId: UUID)(implicit conn: Connection): Option[Subscription] =
    SQL"""
        SELECT id, user_id, name, cadence, interval_days, payment_method, category_id, created_at, updated_at
        FROM subscription
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        FOR UPDATE
        """.as(subscriptionParser.singleOpt)

  def count(userId: UUID)(implicit conn: Connection): Long =
    SQL"""
        SELECT COUNT(*)
        FROM subscription
        WHERE user_id = ${userId.toString}::UUID
        """.as(SqlParser.scalar[Long].single)

  def update(
      id: UUID,
      userId: UUID,
      name: String,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE subscription
        SET name = $name,
            cadence = ${cadence.entryName},
            interval_days = $intervalDays,
            payment_method = ${paymentMethod.entryName},
            category_id = ${categoryId.map(_.toString)}::UUID,
            updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Subscription not found")
  }

  def touch(id: UUID, userId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE subscription
        SET updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Subscription not found")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM subscription
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Subscription not found")
  }
}
