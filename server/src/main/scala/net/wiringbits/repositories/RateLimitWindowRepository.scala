package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.models.RateLimitKey
import net.wiringbits.repositories.daos.RateLimitWindowDAO
import play.api.db.Database

import javax.inject.Inject
import scala.concurrent.Future

class RateLimitWindowRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {

  /** Whether a slot was available in the current one-minute window for `key` (and was consumed). */
  def tryAcquire(key: RateLimitKey, limit: Int): Future[Boolean] = Future {
    database.withConnection { implicit conn =>
      RateLimitWindowDAO.tryConsume(key.entryName, limit).isDefined
    }
  }
}
