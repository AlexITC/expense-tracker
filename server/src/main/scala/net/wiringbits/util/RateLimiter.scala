package net.wiringbits.util

import net.wiringbits.models.RateLimitKey

import scala.concurrent.Future

/** Lets `apis` clients ask for a rate-limit slot without depending on the repository layer. */
trait RateLimiter {

  /** Whether a slot was available (and consumed) for `key` under `limit` requests in the current window. */
  def tryAcquire(key: RateLimitKey, limit: Int): Future[Boolean]
}

object RateLimiter {

  /** Grants every request. For one-off ops tooling running in a single process, with no shared limit to coordinate. */
  val AlwaysGrant: RateLimiter = new RateLimiter {
    override def tryAcquire(key: RateLimitKey, limit: Int): Future[Boolean] = Future.successful(true)
  }
}
