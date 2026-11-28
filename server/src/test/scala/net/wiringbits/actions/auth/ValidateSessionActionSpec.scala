package net.wiringbits.actions.auth

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.UserSession
import net.wiringbits.util.EmailMessage
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

class ValidateSessionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): ValidateSessionAction =
    new ValidateSessionAction(repositories.users)(using executionContext)

  "apply" should {
    "accept the current session version" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      newAction().apply(UserSession(user.id, 0)).futureValue must be(user.id)
    }

    "accept the version returned by a password update" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val version =
        repositories.users.updatePassword(user.id, "new", EmailMessage.updatePassword(user.name)).futureValue

      newAction().apply(UserSession(user.id, version)).futureValue must be(user.id)
    }

    "reject a stale session version" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.users.updatePassword(user.id, "new", EmailMessage.updatePassword(user.name)).futureValue

      val error = newAction().apply(UserSession(user.id, 0)).failed.futureValue
      error.getMessage must be("The session was revoked")
    }

    "reject an unknown user" in withRepositories() { implicit repositories =>
      val error = newAction().apply(UserSession(UUID.randomUUID(), 0)).failed.futureValue
      error.getMessage must be("The session user doesn't exist")
    }
  }
}
