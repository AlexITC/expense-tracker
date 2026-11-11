package net.wiringbits.repositories.daos

import java.sql.Connection

object RateLimitWindowDAO {

  import anorm.*

  /** Atomically consumes one slot from the current one-minute window for `key`, returning the new count, or None when
    * the window already reached `limit` (nothing is updated then).
    *
    * `now()` is used deliberately instead of an injected Clock: the server instances are stateless, so the shared DB
    * clock is the only clock they all agree on for window boundaries.
    */
  def tryConsume(key: String, limit: Int)(implicit conn: Connection): Option[Int] = {
    SQL"""
        INSERT INTO rate_limit_window (key, window_start, count)
        VALUES ($key, date_trunc('minute', now()), 1)
        ON CONFLICT (key) DO UPDATE SET
          window_start = date_trunc('minute', now()),
          count = CASE
            WHEN rate_limit_window.window_start = date_trunc('minute', now()) THEN rate_limit_window.count + 1
            ELSE 1
          END
        WHERE rate_limit_window.window_start < date_trunc('minute', now())
          OR rate_limit_window.count < $limit
        RETURNING count
        """.as(SqlParser.int("count").singleOpt)
  }
}
