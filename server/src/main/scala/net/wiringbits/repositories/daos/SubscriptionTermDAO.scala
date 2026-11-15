package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.SubscriptionTerm

import java.sql.Connection
import java.time.{Instant, LocalDate}
import java.util.UUID

object SubscriptionTermDAO {

  import anorm.*

  def create(term: SubscriptionTerm)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO subscription_term (
          id, user_id, subscription_id, effective_from, effective_to, amount_min, amount_max, created_at, updated_at
        )
        VALUES (
          ${term.id.toString}::UUID,
          ${term.userId.toString}::UUID,
          ${term.subscriptionId.toString}::UUID,
          ${term.effectiveFrom},
          ${term.effectiveTo},
          ${term.amountMin},
          ${term.amountMax},
          ${term.createdAt},
          ${term.updatedAt}
        )
        """.executeUpdate()
  }

  // Only an open term can be closed, so a stale plan cannot overwrite an end date that is already set.
  def close(termId: UUID, subscriptionId: UUID, userId: UUID, effectiveTo: LocalDate, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
        UPDATE subscription_term
        SET effective_to = $effectiveTo,
            updated_at = $updatedAt
        WHERE id = ${termId.toString}::UUID
          AND subscription_id = ${subscriptionId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
          AND effective_to IS NULL
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Subscription term not found")
  }

  def allForUser(userId: UUID)(implicit conn: Connection): List[SubscriptionTerm] =
    SQL"""
        SELECT id, user_id, subscription_id, effective_from, effective_to, amount_min, amount_max, created_at,
               updated_at
        FROM subscription_term
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY subscription_id, effective_from
        """.as(subscriptionTermParser.*)

  def forSubscription(subscriptionId: UUID, userId: UUID)(implicit conn: Connection): List[SubscriptionTerm] =
    SQL"""
        SELECT id, user_id, subscription_id, effective_from, effective_to, amount_min, amount_max, created_at,
               updated_at
        FROM subscription_term
        WHERE subscription_id = ${subscriptionId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        ORDER BY subscription_id, effective_from
        """.as(subscriptionTermParser.*)
}
