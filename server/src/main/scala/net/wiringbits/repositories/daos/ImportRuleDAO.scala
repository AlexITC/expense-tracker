package net.wiringbits.repositories.daos

import anorm.*
import anorm.SqlParser.*
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.repositories.models.ImportRule

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object ImportRuleDAO {

  private val ruleParser: RowParser[ImportRule] =
    (get[UUID]("id") ~
      get[UUID]("user_id") ~
      str("action") ~
      str("pattern") ~
      get[Option[UUID]]("merchant_id") ~
      get[Option[String]]("merchant_name") ~
      get[Option[UUID]]("category_id") ~
      get[Option[String]]("category_name") ~
      bool("auto_created") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at")).map {
      case id ~ userId ~ action ~ pattern ~ merchantId ~ merchantName ~ categoryId ~ categoryName ~ autoCreated ~ createdAt ~ updatedAt =>
        ImportRule(
          id,
          userId,
          ImportRuleAction.fromColumns(action, merchantId, categoryId),
          pattern,
          merchantName,
          categoryName,
          autoCreated,
          createdAt,
          updatedAt
        )
    }

  def create(rule: ImportRule.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      INSERT INTO merchant_rule (id, user_id, action, pattern, merchant_id, category_id, auto_created, created_at, updated_at)
      VALUES (
        ${rule.id.toString}::UUID,
        ${rule.userId.toString}::UUID,
        ${rule.action.entryName},
        ${rule.pattern},
        ${rule.action.merchantIdOpt.map(_.toString)}::UUID,
        ${rule.action.categoryIdOpt.map(_.toString)}::UUID,
        ${rule.autoCreated},
        ${rule.createdAt},
        ${rule.updatedAt}
      )
      """.executeUpdate()
  }

  // Inserts an auto-created set_merchant rule, leaving an already-present matching rule untouched. The
  // merchant_rule_set_merchant_unique partial index is the arbiter, so concurrent callers converge on one rule.
  def createSetMerchantIfAbsent(rule: ImportRule.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      INSERT INTO merchant_rule (id, user_id, action, pattern, merchant_id, category_id, auto_created, created_at, updated_at)
      VALUES (
        ${rule.id.toString}::UUID,
        ${rule.userId.toString}::UUID,
        ${rule.action.entryName},
        ${rule.pattern},
        ${rule.action.merchantIdOpt.map(_.toString)}::UUID,
        ${rule.action.categoryIdOpt.map(_.toString)}::UUID,
        ${rule.autoCreated},
        ${rule.createdAt},
        ${rule.updatedAt}
      )
      ON CONFLICT (user_id, pattern, merchant_id) WHERE action = 'set_merchant' DO NOTHING
      """.executeUpdate()
  }

  def all(userId: UUID)(implicit conn: Connection): List[ImportRule] =
    SQL"""
      SELECT r.id, r.user_id, r.action, r.pattern, r.merchant_id,
             m.name AS merchant_name, r.category_id, c.name AS category_name,
             r.auto_created, r.created_at, r.updated_at
      FROM merchant_rule r
      LEFT JOIN merchant m ON r.merchant_id = m.id
      LEFT JOIN category c ON r.category_id = c.id
      WHERE r.user_id = ${userId.toString}::UUID
      ORDER BY r.created_at DESC, r.id
      """.as(ruleParser.*)

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[ImportRule] =
    SQL"""
      SELECT r.id, r.user_id, r.action, r.pattern, r.merchant_id,
             m.name AS merchant_name, r.category_id, c.name AS category_name,
             r.auto_created, r.created_at, r.updated_at
      FROM merchant_rule r
      LEFT JOIN merchant m ON r.merchant_id = m.id
      LEFT JOIN category c ON r.category_id = c.id
      WHERE r.id = ${id.toString}::UUID
        AND r.user_id = ${userId.toString}::UUID
      """.as(ruleParser.singleOpt)

  def update(
      id: UUID,
      userId: UUID,
      action: ImportRuleAction,
      pattern: String,
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE merchant_rule
      SET action = ${action.entryName},
          pattern = $pattern,
          merchant_id = ${action.merchantIdOpt.map(_.toString)}::UUID,
          category_id = ${action.categoryIdOpt.map(_.toString)}::UUID,
          auto_created = FALSE,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Import rule not found")
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
      DELETE FROM merchant_rule
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Import rule not found")
  }
}
