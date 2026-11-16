package net.wiringbits.actions.subscriptions

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class GetSubscriptionStartDateSuggestionActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): GetSubscriptionStartDateSuggestionAction =
    new GetSubscriptionStartDateSuggestionAction(repositories.subscriptions)(using executionContext)

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  "apply" should {
    "return the earliest expense date for the merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id, name = uniqueName("Streaming")).futureValue
      val otherMerchant = createMerchant(user.id, name = uniqueName("Gym")).futureValue
      createExpense(user.id, date = LocalDate.of(2025, 5, 10), merchantId = Some(merchant.id)).futureValue
      createExpense(user.id, date = LocalDate.of(2025, 2, 3), merchantId = Some(merchant.id)).futureValue
      createExpense(user.id, date = LocalDate.of(2025, 8, 20), merchantId = Some(merchant.id)).futureValue
      createExpense(user.id, date = LocalDate.of(2024, 1, 1), merchantId = Some(otherMerchant.id)).futureValue

      val response = newAction().apply(userId = user.id, merchantId = merchant.id).futureValue

      response.earliestExpenseDate must be(Some(LocalDate.of(2025, 2, 3)))
    }

    "ignore other users' expenses" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val merchant = createMerchant(user.id, name = uniqueName("Streaming")).futureValue
      createExpense(user.id, date = LocalDate.of(2025, 6, 1), merchantId = Some(merchant.id)).futureValue
      createExpense(other.id, date = LocalDate.of(2024, 1, 1), merchantId = Some(merchant.id)).futureValue

      val response = newAction().apply(userId = user.id, merchantId = merchant.id).futureValue

      response.earliestExpenseDate must be(Some(LocalDate.of(2025, 6, 1)))
    }

    "return None when the merchant has no expenses" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id, name = uniqueName("Streaming")).futureValue

      val response = newAction().apply(userId = user.id, merchantId = merchant.id).futureValue

      response.earliestExpenseDate must be(None)
    }

    "fail for another user's merchant" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val merchant = createMerchant(owner.id, name = uniqueName("Streaming")).futureValue
      createExpense(owner.id, date = LocalDate.of(2025, 6, 1), merchantId = Some(merchant.id)).futureValue

      val error = newAction().apply(userId = other.id, merchantId = merchant.id).failed.futureValue

      error.getMessage must include("Merchant not found")
    }
  }
}
