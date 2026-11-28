package net.wiringbits.repositories

import net.wiringbits.common.models.{Email, Name}
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.jobs.BackgroundJobType
import net.wiringbits.repositories.daos.backgroundJobParser
import net.wiringbits.repositories.models.{BackgroundJobData, User}
import net.wiringbits.util.EmailMessage
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{RandomData, RepositoryUtils}

import java.util.UUID
class UsersRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "work" in withRepositories() { implicit repositories =>
      createUser().futureValue
    }

    "create a token for verifying the email" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val response = repositories.userTokens.find(request.id).futureValue
      response.nonEmpty must be(true)
    }

    "fail when the id already exists" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      val ex = intercept[RuntimeException] {
        repositories.users.create(request.copy(email = RandomData.randomEmail())).futureValue
      }
      // TODO: This should be a better message
      ex.getCause.getMessage must startWith(
        """ERROR: duplicate key value violates unique constraint "users_user_id_pk""""
      )
    }

    "fail when the email already exists" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      val ex = intercept[RuntimeException] {
        repositories.users.create(request.copy(id = UUID.randomUUID())).futureValue
      }
      ex.getCause.getMessage must include("The email is not available")
    }
  }

  "all" should {
    "return the existing users" in withRepositories() { implicit repositories =>
      val expected = List.fill(3)(createUser().futureValue)

      val response = repositories.users.all().futureValue
      response.map(u => (u.id, u.email)) must contain allElementsOf expected.map(u => (u.id, u.email))
    }
  }

  "find(email)" should {
    "return a user when the email exists" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val response = repositories.users.find(request.email).futureValue
      response.value.email must be(request.email)
      response.value.id must be(request.id)
    }

    "return a user when the email exists (case insensitive match)" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val email = Email.trusted(request.email.string.toUpperCase)
      val response = repositories.users.find(email).futureValue
      response.isDefined must be(true)
    }

    "return no result when the email doesn't exists" in withRepositories() { repositories =>
      val email = RandomData.randomEmail()
      val response = repositories.users.find(email).futureValue
      response.isEmpty must be(true)
    }
  }

  "find(id)" should {
    "return a user when the id exists" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val response = repositories.users.find(request.id).futureValue
      response.value.email must be(request.email)
      response.value.id must be(request.id)
    }

    "return no result when the id doesn't exists" in withRepositories() { repositories =>
      val id = UUID.randomUUID()
      val response = repositories.users.find(id).futureValue
      response.isEmpty must be(true)
    }
  }

  "update" should {
    "update an existing user" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val newName = Name.trusted("Test")
      repositories.users.update(request.id, newName).futureValue

      val response = repositories.users.find(request.id).futureValue
      response.value.name must be(newName)
      response.value.email must be(request.email)
    }
  }

  "updatePassword" should {
    "update the password for an existing user" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val newPassword = "test"
      repositories.users.updatePassword(request.id, newPassword, EmailMessage.updatePassword(request.name)).futureValue

      repositories.users.findHashedPassword(request.id).futureValue must be(Some(newPassword))
    }

    "produce a notification for the user" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val newPassword = "test"
      repositories.users.updatePassword(request.id, newPassword, EmailMessage.updatePassword(request.name)).futureValue

      sendEmailJobsOf(request.email).map(_.`type`) must be(List(BackgroundJobType.SendEmail))
    }

    "remove every token of the user, keeping other users' tokens" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      createToken(request.id).futureValue
      val other = createUser().futureValue
      createToken(other.id).futureValue

      repositories.users.updatePassword(request.id, "test", EmailMessage.updatePassword(request.name)).futureValue

      repositories.userTokens.find(request.id).futureValue must be(empty)
      repositories.userTokens.find(other.id).futureValue.size must be(2)
    }

    "fail when the user doesn't exist" in withRepositories() { repositories =>
      val name = Name.trusted("test")
      val ex = intercept[RuntimeException] {
        repositories.users.updatePassword(UUID.randomUUID(), "test", EmailMessage.updatePassword(name)).futureValue
      }
      ex.getCause.getMessage must be("SqlMappingError(No rows when expecting a single one)")
    }
  }

  "verify" should {
    "verify a user given a token" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      repositories.users.verify(request.id, UUID.randomUUID(), EmailMessage.confirm(request.name)).futureValue

      val response = repositories.users.find(request.id).futureValue
      response.value.verifiedOn.isDefined must be(true)
    }

    "produce a notification for the user" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      repositories.users.verify(request.id, UUID.randomUUID(), EmailMessage.confirm(request.name)).futureValue

      sendEmailJobsOf(request.email).map(_.`type`) must be(List(BackgroundJobType.SendEmail))
    }
  }

  "resetPassword" should {
    "update the password" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val newPassword = "test"
      repositories.users.resetPassword(request.id, newPassword, EmailMessage.resetPassword(request.name)).futureValue

      repositories.users.findHashedPassword(request.id).futureValue must be(Some(newPassword))
    }

    "produce a notification for the user" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      val newPassword = "test"
      repositories.users.resetPassword(request.id, newPassword, EmailMessage.resetPassword(request.name)).futureValue

      sendEmailJobsOf(request.email).map(_.`type`) must be(List(BackgroundJobType.SendEmail))
    }

    "remove every token of the user, keeping other users' tokens" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      createToken(request.id).futureValue
      val other = createUser().futureValue
      createToken(other.id).futureValue

      repositories.users.resetPassword(request.id, "test", EmailMessage.resetPassword(request.name)).futureValue

      repositories.userTokens.find(request.id).futureValue must be(empty)
      repositories.userTokens.find(other.id).futureValue.size must be(2)
    }

    "fail when the user doesn't exist" in withRepositories() { repositories =>
      val name = Name.trusted("test")
      val ex = intercept[RuntimeException] {
        repositories.users.resetPassword(UUID.randomUUID(), "test", EmailMessage.resetPassword(name)).futureValue
      }
      ex.getCause.getMessage must be("SqlMappingError(No rows when expecting a single one)")
    }
  }

  "findHashedPassword" should {
    "return the stored hash for an existing user" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue

      repositories.users.findHashedPassword(request.id).futureValue must be(Some(request.hashedPassword))
    }

    "return no result when the user doesn't exist" in withRepositories() { repositories =>
      repositories.users.findHashedPassword(UUID.randomUUID()).futureValue must be(None)
    }
  }

  "session version" should {
    "be incremented by resetPassword, keeping other users' versions" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      val other = createUser().futureValue

      repositories.users.resetPassword(request.id, "test", EmailMessage.resetPassword(request.name)).futureValue

      repositories.users.find(request.id).futureValue.value.sessionVersion must be(1)
      repositories.users.find(other.id).futureValue.value.sessionVersion must be(0)
    }

    "be incremented by updatePassword, which returns the new version" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      val other = createUser().futureValue

      val response =
        repositories.users.updatePassword(request.id, "test", EmailMessage.updatePassword(request.name)).futureValue

      response must be(1)
      repositories.users.find(request.id).futureValue.value.sessionVersion must be(1)
      repositories.users.find(other.id).futureValue.value.sessionVersion must be(0)
    }

    "be incremented by logoutEverywhere, which returns the new version" in withRepositories() { implicit repositories =>
      val request = createUser().futureValue
      val other = createUser().futureValue

      repositories.users.logoutEverywhere(request.id).futureValue must be(1)
      repositories.users.logoutEverywhere(request.id).futureValue must be(2)

      repositories.users.find(request.id).futureValue.value.sessionVersion must be(2)
      repositories.users.find(other.id).futureValue.value.sessionVersion must be(0)
    }
  }

  "logoutEverywhere" should {
    "fail when the user doesn't exist" in withRepositories() { repositories =>
      val ex = intercept[RuntimeException] {
        repositories.users.logoutEverywhere(UUID.randomUUID()).futureValue
      }
      ex.getCause.getMessage must be("SqlMappingError(No rows when expecting a single one)")
    }
  }

  // No repository API reads background jobs back, so inspect the stored rows for the given recipient directly.
  private def sendEmailJobsOf(email: Email)(using repositories: RepositoryComponents): List[BackgroundJobData] =
    repositories.database.withConnection { implicit conn =>
      anorm
        .SQL("SELECT * FROM background_jobs WHERE payload->>'email' = {email}")
        .on("email" -> email.string)
        .as(backgroundJobParser.*)
    }
}
