package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class GetSubscriptionsActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): GetSubscriptionsAction =
    new GetSubscriptionsAction(repositories.subscriptions)(using executionContext)

  "apply" should {
    "return only the user's subscriptions ordered by name" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val base = UUID.randomUUID().toString
      createSubscription(user.id, name = s"$base B").futureValue
      createSubscription(user.id, name = s"$base A").futureValue
      createSubscription(other.id, name = s"$base C").futureValue

      val response = newAction().apply(user.id).futureValue
      response.data.map(_.name) must be(List(s"$base A", s"$base B"))
    }

    "map every stored field" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = s"Category ${UUID.randomUUID()}").futureValue
      val existing = createSubscription(
        user.id,
        name = s"Water ${UUID.randomUUID()}",
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(45),
        amountMin = Some(BigDecimal("10.00")),
        amountMax = Some(BigDecimal("15.50")),
        paymentMethod = SubscriptionPaymentMethod.ManualTransfer,
        categoryId = Some(category.id),
        startDate = LocalDate.of(2024, 4, 10)
      ).futureValue

      val item = newAction().apply(user.id).futureValue.data.find(_.id == existing.id).value
      item.name must be(existing.name)
      item.cadence must be(SubscriptionCadence.EveryNDays)
      item.intervalDays must be(Some(45))
      item.amountMin must be(Some(BigDecimal("10.00")))
      item.amountMax must be(Some(BigDecimal("15.50")))
      item.paymentMethod must be(SubscriptionPaymentMethod.ManualTransfer)
      item.categoryId must be(Some(category.id))
      item.startDate must be(LocalDate.of(2024, 4, 10))
    }
  }
}
