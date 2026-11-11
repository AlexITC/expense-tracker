package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.RateLimitKey
import net.wiringbits.repositories.daos.RateLimitWindowDAO
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class RateLimitWindowRepositorySpec extends RepositorySpec with RepositoryUtils {

  // The DB is shared across specs, so every case uses its own random key.
  private def randomKey(): String = UUID.randomUUID().toString

  // A fixed window instant, so each case controls the window boundary instead of depending on the wall clock.
  private val windowAt = Instant.parse("2026-01-01T10:30:00Z")

  "tryConsume" should {
    "count consecutive consumes under the limit" in withRepositories() { implicit repositories =>
      val key = randomKey()
      val result = repositories.database.withConnection { implicit conn =>
        List.fill(3)(RateLimitWindowDAO.tryConsume(key, 3, at = Some(windowAt)))
      }
      result must be(List(Some(1), Some(2), Some(3)))
    }

    "reject a consume over the limit without changing the count" in withRepositories() { implicit repositories =>
      val key = randomKey()
      val result = repositories.database.withConnection { implicit conn =>
        List.fill(4)(RateLimitWindowDAO.tryConsume(key, 2, at = Some(windowAt)))
      }
      result must be(List(Some(1), Some(2), None, None))
      findRateLimitCount(key) must be(Some(2))
    }

    "reset the count once the window advances" in withRepositories() { implicit repositories =>
      val key = randomKey()
      val before = repositories.database.withConnection { implicit conn =>
        List.fill(2)(RateLimitWindowDAO.tryConsume(key, 2, at = Some(windowAt)))
      }
      before must be(List(Some(1), Some(2)))

      val after = repositories.database.withConnection { implicit conn =>
        RateLimitWindowDAO.tryConsume(key, 2, at = Some(windowAt.plus(1, ChronoUnit.MINUTES)))
      }
      after must be(Some(1))
    }

    "keep different keys independent" in withRepositories() { implicit repositories =>
      val keyA = randomKey()
      val keyB = randomKey()
      val result = repositories.database.withConnection { implicit conn =>
        List(
          RateLimitWindowDAO.tryConsume(keyA, 1, at = Some(windowAt)),
          RateLimitWindowDAO.tryConsume(keyA, 1, at = Some(windowAt)),
          RateLimitWindowDAO.tryConsume(keyB, 1, at = Some(windowAt))
        )
      }
      result must be(List(Some(1), None, Some(1)))
    }
  }

  "tryAcquire" should {
    "succeed while the limit isn't reached" in withRepositories() { implicit repositories =>
      repositories.rateLimitWindows.tryAcquire(RateLimitKey.Bedrock, Int.MaxValue).futureValue must be(true)
    }
  }
}
