package net.wiringbits.repositories

import net.wiringbits.common.models.Email
import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.Instant
import java.util.UUID

class McpRepositorySpec extends RepositorySpec with RepositoryUtils {

  private val createdAt = Instant.now()

  "create" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.mcp.create(UUID.randomUUID(), user.id, "a" * 64, createdAt).futureValue
    }

    "fail when the user doesn't exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        repositories.mcp.create(UUID.randomUUID(), UUID.randomUUID(), "a" * 64, createdAt).futureValue
      }
      ex.getCause.getMessage must include("mcp_api_token_users_fk")
    }

    "fail on duplicate token hash across different users" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser(Email.trusted(s"other${UUID.randomUUID()}@wiringbits.net")).futureValue
      val tokenHash = "b" * 64
      repositories.mcp.create(UUID.randomUUID(), userA.id, tokenHash, createdAt).futureValue

      val ex = intercept[RuntimeException] {
        repositories.mcp.create(UUID.randomUUID(), userB.id, tokenHash, createdAt).futureValue
      }
      ex.getCause.getMessage must include("mcp_api_token_token_hash_unique")
    }

    "revoke the user's previous token when a new one is created" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val oldHash = "e" * 64
      val newHash = "f" * 64
      repositories.mcp.create(UUID.randomUUID(), user.id, oldHash, createdAt).futureValue

      repositories.mcp.create(UUID.randomUUID(), user.id, newHash, createdAt).futureValue

      repositories.mcp.findByTokenHash(oldHash).futureValue must be(None)
      repositories.mcp.findByTokenHash(newHash).futureValue must not be None
    }
  }

  "findByTokenHash" should {
    "return the token and set last_used_at when it exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = UUID.randomUUID()
      val tokenHash = "c" * 64
      repositories.mcp.create(id, user.id, tokenHash, createdAt).futureValue

      val result = repositories.mcp.findByTokenHash(tokenHash).futureValue.value
      result.id must be(id)
      result.userId must be(user.id)
      result.lastUsedAt must be(None)

      val secondLookup = repositories.mcp.findByTokenHash(tokenHash).futureValue.value
      secondLookup.lastUsedAt must not be None
    }

    "return None when the token doesn't exist" in withRepositories() { implicit repositories =>
      repositories.mcp.findByTokenHash("d" * 64).futureValue must be(None)
    }
  }
}
