package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class TelegramRepositorySpec extends RepositorySpec with RepositoryUtils {

  "createLinkToken" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.telegram.createLinkToken("abc123", user.id, Instant.now().plus(1, ChronoUnit.DAYS)).futureValue
    }

    "fail when the user doesn't exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        repositories.telegram
          .createLinkToken("abc123", UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.DAYS))
          .futureValue
      }
      ex.getCause.getMessage must include("telegram_link_token_users_fk")
    }

    "fail on duplicate token" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.telegram.createLinkToken("duptoken1", user.id, Instant.now().plus(1, ChronoUnit.DAYS)).futureValue

      val ex = intercept[RuntimeException] {
        repositories.telegram.createLinkToken("duptoken1", user.id, Instant.now().plus(1, ChronoUnit.DAYS)).futureValue
      }
      ex.getCause.getMessage must include("telegram_link_token_token_unique")
    }
  }

  "findLinkToken" should {
    "return the token when it exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val token = "findme01"
      val expiresAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
      repositories.telegram.createLinkToken(token, user.id, expiresAt).futureValue

      val result = repositories.telegram.findLinkToken(token).futureValue.value
      result.token must be(token)
      result.userId must be(user.id)
      result.expiresAt must be(expiresAt)
    }

    "return None when the token doesn't exist" in withRepositories() { repositories =>
      val result = repositories.telegram.findLinkToken("nonexistent").futureValue
      result must be(None)
    }
  }

  "deleteLinkToken" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val token = "todelete1"
      repositories.telegram.createLinkToken(token, user.id, Instant.now().plus(1, ChronoUnit.DAYS)).futureValue

      repositories.telegram.deleteLinkToken(token).futureValue

      repositories.telegram.findLinkToken(token).futureValue must be(None)
    }

    "be a no-op when the token doesn't exist" in withRepositories() { repositories =>
      repositories.telegram.deleteLinkToken("nonexistent").futureValue
    }
  }

  "createBotUser" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.telegram.createBotUser(UUID.randomUUID(), user.id, 100L).futureValue
    }

    "fail when the user doesn't exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        repositories.telegram.createBotUser(UUID.randomUUID(), UUID.randomUUID(), 200L).futureValue
      }
      ex.getCause.getMessage must include("telegram_bot_user_users_fk")
    }

    "fail on duplicate telegram_user_id" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.telegram.createBotUser(UUID.randomUUID(), user.id, 300L).futureValue

      val ex = intercept[RuntimeException] {
        repositories.telegram.createBotUser(UUID.randomUUID(), user.id, 300L).futureValue
      }
      ex.getCause.getMessage must include("telegram_bot_user_telegram_user_id_unique")
    }
  }

  "findBotUserByTelegramId" should {
    "return the bot user when it exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val telegramUserId = 400L
      val id = UUID.randomUUID()
      repositories.telegram.createBotUser(id, user.id, telegramUserId).futureValue

      val result = repositories.telegram.findBotUserByTelegramId(telegramUserId).futureValue.value
      result.telegramBotUserId must be(id)
      result.userId must be(user.id)
      result.telegramUserId must be(telegramUserId)
    }

    "return None when the telegram user doesn't exist" in withRepositories() { repositories =>
      repositories.telegram.findBotUserByTelegramId(999L).futureValue must be(None)
    }
  }
}
