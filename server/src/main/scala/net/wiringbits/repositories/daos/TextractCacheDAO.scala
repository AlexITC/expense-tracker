package net.wiringbits.repositories.daos

import anorm.postgresql.*
import net.wiringbits.repositories.models.TextractCache

import java.sql.Connection

object TextractCacheDAO {

  import anorm.*

  def create(request: TextractCache.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO textract_cache (sha256, api_type, response, created_at)
        VALUES (${request.sha256}, ${request.apiType}, ${request.response}, ${request.createdAt})
        ON CONFLICT (sha256, api_type) DO NOTHING
        """.executeUpdate()
  }

  def find(sha256: String, apiType: String)(implicit conn: Connection): Option[TextractCache] = {
    SQL"""
        SELECT sha256, api_type, response, created_at
        FROM textract_cache
        WHERE sha256 = $sha256
          AND api_type = $apiType
        """.as(textractCacheParser.singleOpt)
  }
}
