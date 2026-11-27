package net.wiringbits.actions.auth

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

class LogoutEverywhereActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): LogoutEverywhereAction =
    new LogoutEverywhereAction(repositories.users)(using executionContext)

  "apply" should {
    "increment the session version and record it in the user logs" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      newAction().apply(user.id).futureValue

      repositories.users.findSessionVersion(user.id).futureValue must be(Some(1))
      val logs = repositories.userLogs.logs(user.id).futureValue
      logs.count(_.message == "Logged out of all sessions") must be(1)
    }

    "not affect other users" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue

      newAction().apply(user.id).futureValue

      repositories.users.findSessionVersion(other.id).futureValue must be(Some(0))
      repositories.userLogs.logs(other.id).futureValue.count(_.message == "Logged out of all sessions") must be(0)
    }
  }
}
