package net.wiringbits.repositories.daos

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.repositories.models.Subscription

import java.sql.Connection
import java.time.{Instant, LocalDate}
import java.util.UUID

object SubscriptionDAO {

  import anorm.*

  def create(subscription: Subscription.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO subscription (
          id, user_id, name, cadence, interval_days, amount_min, amount_max, payment_method, category_id,
          start_date, created_at, updated_at
        )
        VALUES (
          ${subscription.id.toString}::UUID,
          ${subscription.userId.toString}::UUID,
          ${subscription.name},
          ${subscription.cadence.entryName},
          ${subscription.intervalDays},
          ${subscription.amountMin},
          ${subscription.amountMax},
          ${subscription.paymentMethod.entryName},
          ${subscription.categoryId.map(_.toString)}::UUID,
          ${subscription.startDate},
          ${subscription.createdAt},
          ${subscription.updatedAt}
        )
        """.executeUpdate()
  }

  def all(userId: UUID)(implicit conn: Connection): List[Subscription] =
    SQL"""
        SELECT id, user_id, name, cadence, interval_days, amount_min, amount_max, payment_method, category_id,
               start_date, created_at, updated_at
        FROM subscription
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY name
        """.as(subscriptionParser.*)

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Subscription] =
    SQL"""
        SELECT id, user_id, name, cadence, interval_days, amount_min, amount_max, payment_method, category_id,
               start_date, created_at, updated_at
        FROM subscription
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
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
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      startDate: LocalDate,
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE subscription
        SET name = $name,
            cadence = ${cadence.entryName},
            interval_days = $intervalDays,
            amount_min = $amountMin,
            amount_max = $amountMax,
            payment_method = ${paymentMethod.entryName},
            category_id = ${categoryId.map(_.toString)}::UUID,
            start_date = $startDate,
            updated_at = $updatedAt
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
