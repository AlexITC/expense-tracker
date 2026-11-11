package net.wiringbits.repositories.daos

import java.sql.Connection
import java.time.Instant

object RateLimitWindowDAO {

  import anorm.*

  /** Atomically consumes one slot from the current one-minute window for `key`, returning the new count, or None when
    * the window already reached `limit` (nothing is updated then).
    *
    * The window boundary is resolved from the DB clock (`now()`): the server instances are stateless, so the shared DB
    * clock is the only clock they all agree on. `at` overrides it so tests can pin the window deterministically;
    * production always passes None.
    */
  def tryConsume(key: String, limit: Int, at: Option[Instant])(implicit conn: Connection): Option[Int] = {
    SQL"""
        WITH w AS (
          SELECT date_trunc('minute', COALESCE($at::timestamptz, now())) AS window_start
        )
        INSERT INTO rate_limit_window (key, window_start, count)
        SELECT $key, window_start, 1 FROM w
        ON CONFLICT (key) DO UPDATE SET
          window_start = excluded.window_start,
          count = CASE
            WHEN rate_limit_window.window_start = excluded.window_start THEN rate_limit_window.count + 1
            ELSE 1
          END
        WHERE rate_limit_window.window_start < excluded.window_start
          OR rate_limit_window.count < $limit
        RETURNING count
        """.as(SqlParser.int("count").singleOpt)
  }
}
