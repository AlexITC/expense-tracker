package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.McpToken

import anorm.SqlStringInterpolation
import java.sql.Connection
import java.time.Instant
import java.util.UUID

object McpDAO {

  def deleteByUserId(userId: UUID)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        DELETE FROM mcp_api_token
        WHERE user_id = ${userId.toString}::UUID
        """.executeUpdate()
  }

  def create(id: UUID, userId: UUID, tokenHash: String, createdAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO mcp_api_token (id, user_id, token_hash, created_at)
        VALUES (
          ${id.toString}::UUID,
          ${userId.toString}::UUID,
          $tokenHash,
          $createdAt
        )
        """.execute()
  }

  def findByTokenHash(tokenHash: String)(implicit conn: Connection): Option[McpToken] = {
    SQL"""
        SELECT id, user_id, token_hash, created_at, last_used_at
        FROM mcp_api_token
        WHERE token_hash = $tokenHash
        """.as(mcpTokenParser.singleOpt)
  }

  def touchLastUsedIfStale(id: UUID)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        UPDATE mcp_api_token
        SET last_used_at = NOW()
        WHERE id = ${id.toString}::UUID
          AND (last_used_at IS NULL OR last_used_at < NOW() - INTERVAL '5 minutes')
        """.executeUpdate()
  }
}
