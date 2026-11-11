package net.wiringbits.services

import net.wiringbits.models.RateLimitKey
import net.wiringbits.repositories.RateLimitWindowRepository
import net.wiringbits.util.RateLimiter

import javax.inject.Inject
import scala.concurrent.Future

/** Fixed one-minute window shared through the database, so the limit holds across all server instances. */
class DbRateLimiter @Inject() (repo: RateLimitWindowRepository) extends RateLimiter {
  override def tryAcquire(key: RateLimitKey, limit: Int): Future[Boolean] = repo.tryAcquire(key, limit)
}
