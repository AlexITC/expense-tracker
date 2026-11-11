package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.BankAccountAlias

import java.sql.Connection
import java.time.Instant
import java.util.UUID

object BankAccountAliasDAO {

  import anorm.*

  /** Creates an empty alias for the account unless one already exists, so an existing nickname is never clobbered. */
  def ensureExists(id: UUID, userId: UUID, key: BankAccountAlias.Key, now: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO bank_account_alias
          (id, user_id, bank_name, product_type, account_last4, currency, alias, created_at, updated_at)
        VALUES (
          ${id.toString}::UUID,
          ${userId.toString}::UUID,
          ${key.bankName},
          ${key.productType},
          ${key.accountLast4},
          ${key.currency},
          '',
          $now,
          $now
        )
        ON CONFLICT ON CONSTRAINT bank_account_alias_user_account_unique DO NOTHING
        """.executeUpdate()
  }

  def upsert(id: UUID, userId: UUID, key: BankAccountAlias.Key, alias: String, now: Instant)(implicit
      conn: Connection
  ): Unit = {
    val _ = SQL"""
        INSERT INTO bank_account_alias
          (id, user_id, bank_name, product_type, account_last4, currency, alias, created_at, updated_at)
        VALUES (
          ${id.toString}::UUID,
          ${userId.toString}::UUID,
          ${key.bankName},
          ${key.productType},
          ${key.accountLast4},
          ${key.currency},
          $alias,
          $now,
          $now
        )
        ON CONFLICT ON CONSTRAINT bank_account_alias_user_account_unique DO UPDATE
        SET alias = EXCLUDED.alias, updated_at = EXCLUDED.updated_at
        WHERE bank_account_alias.user_id = ${userId.toString}::UUID
        """.executeUpdate()
  }

  def find(userId: UUID, key: BankAccountAlias.Key)(implicit conn: Connection): Option[BankAccountAlias] = {
    SQL"""
        SELECT id, user_id, bank_name, product_type, account_last4, currency, alias, created_at, updated_at
        FROM bank_account_alias
        WHERE user_id = ${userId.toString}::UUID
          AND bank_name = ${key.bankName}
          AND product_type = ${key.productType}
          AND account_last4 = ${key.accountLast4}
          AND currency = ${key.currency}
        """.as(bankAccountAliasParser.singleOpt)
  }

}
