package net.wiringbits.actions.subscriptions

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

class DeleteSubscriptionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): DeleteSubscriptionAction =
    new DeleteSubscriptionAction(repositories.subscriptions)(using executionContext)

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  "apply" should {
    "remove the subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(user.id, name = uniqueName("Phone")).futureValue

      newAction().apply(subscriptionId = existing.id, userId = user.id).futureValue

      repositories.subscriptions.find(id = existing.id, userId = user.id).futureValue must be(None)
    }

    "fail for another user's subscription and keep it" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(owner.id, name = uniqueName("Phone")).futureValue

      val error = newAction().apply(subscriptionId = existing.id, userId = other.id).failed.futureValue
      error.getMessage must include("Subscription not found")
      repositories.subscriptions.find(id = existing.id, userId = owner.id).futureValue must not be empty
    }
  }
}
