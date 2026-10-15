package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.McpDAO
import net.wiringbits.repositories.models.McpToken
import play.api.db.Database

import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class McpRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {

  // Only one live token per user: generating a new one revokes any previous token, matching the
  // "Regenerate" affordance in the profile page UI.
  def create(id: UUID, userId: UUID, tokenHash: String, createdAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      McpDAO.deleteByUserId(userId)
      McpDAO.create(id, userId, tokenHash, createdAt)
    }
  }

  def findByTokenHash(tokenHash: String): Future[Option[McpToken]] = Future {
    database.withTransaction { implicit conn =>
      val token = McpDAO.findByTokenHash(tokenHash)
      // Opportunistic: only write last_used_at when it's stale, so a burst of MCP calls on the
      // same token doesn't serialize on a write to this row for every single request.
      token.foreach(t => McpDAO.touchLastUsedIfStale(t.id))
      token
    }
  }
}
