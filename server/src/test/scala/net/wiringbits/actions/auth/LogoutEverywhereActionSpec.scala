package net.wiringbits.actions.auth

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

class LogoutEverywhereActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): LogoutEverywhereAction =
    new LogoutEverywhereAction(repositories.users)(using executionContext)

  "apply" should {
    "increment the session version" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      newAction().apply(user.id).futureValue

      repositories.users.find(user.id).futureValue.value.sessionVersion must be(1)
    }

    "not affect other users" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue

      newAction().apply(user.id).futureValue

      repositories.users.find(other.id).futureValue.value.sessionVersion must be(0)
    }
  }
}
