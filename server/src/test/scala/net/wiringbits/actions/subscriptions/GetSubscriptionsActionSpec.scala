package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod, SubscriptionTerm}
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.models.SubscriptionTerm as Term
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Instant, LocalDate}
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

    "map an every-n-months interval" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createSubscription(
        user.id,
        name = s"Insurance ${UUID.randomUUID()}",
        cadence = SubscriptionCadence.EveryNMonths,
        intervalMonths = Some(3)
      ).futureValue

      val item = newAction().apply(user.id).futureValue.data.find(_.id == existing.id).value
      item.cadence must be(SubscriptionCadence.EveryNMonths)
      item.intervalDays must be(None)
      item.intervalMonths must be(Some(3))
    }

    "map every stored field" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = s"Category ${UUID.randomUUID()}").futureValue
      val merchant = createMerchant(user.id, name = s"Merchant ${UUID.randomUUID()}").futureValue
      val existing = createSubscription(
        user.id,
        name = s"Water ${UUID.randomUUID()}",
        cadence = SubscriptionCadence.EveryNDays,
        intervalDays = Some(45),
        amountMin = Some(BigDecimal("10.00")),
        amountMax = Some(BigDecimal("15.50")),
        paymentMethod = SubscriptionPaymentMethod.ManualTransfer,
        categoryId = Some(category.id),
        merchantId = Some(merchant.id),
        description = Some("Family plan"),
        startDate = LocalDate.of(2024, 4, 10)
      ).futureValue

      val item = newAction().apply(user.id).futureValue.data.find(_.id == existing.id).value
      item.name must be(existing.name)
      item.cadence must be(SubscriptionCadence.EveryNDays)
      item.intervalDays must be(Some(45))
      item.intervalMonths must be(None)
      item.paymentMethod must be(SubscriptionPaymentMethod.ManualTransfer)
      item.categoryId must be(Some(category.id))
      item.merchantId must be(Some(merchant.id))
      item.description must be(Some("Family plan"))
      item.terms must be(
        List(
          SubscriptionTerm(
            id = existing.firstTerm.id,
            effectiveFrom = LocalDate.of(2024, 4, 10),
            effectiveTo = None,
            amountMin = Some(BigDecimal("10.00")),
            amountMax = Some(BigDecimal("15.50"))
          )
        )
      )
    }

    "return each subscription's terms ordered by effective date" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val existing = createSubscription(user.id, name = s"Gym ${UUID.randomUUID()}").futureValue
      val sibling = createSubscription(user.id, name = s"Pool ${UUID.randomUUID()}").futureValue
      createSubscription(other.id, name = s"Other ${UUID.randomUUID()}").futureValue
      val now = Instant.now()
      def term(from: LocalDate, to: Option[LocalDate], amount: String) = Term(
        id = UUID.randomUUID(),
        userId = user.id,
        subscriptionId = existing.id,
        effectiveFrom = from,
        effectiveTo = to,
        amountMin = Some(BigDecimal(amount)),
        amountMax = Some(BigDecimal(amount)),
        createdAt = now,
        updatedAt = now
      )
      // Inserted out of order so the result order comes from the query, not insertion.
      insertSubscriptionTerm(term(LocalDate.of(2023, 6, 1), Some(LocalDate.of(2023, 9, 1)), "8.00")).futureValue
      insertSubscriptionTerm(term(LocalDate.of(2023, 1, 1), Some(LocalDate.of(2023, 6, 1)), "7.00")).futureValue

      val response = newAction().apply(user.id).futureValue
      response.data.map(_.id).toSet must be(Set(existing.id, sibling.id))
      val item = response.data.find(_.id == existing.id).value
      item.terms.map(_.effectiveFrom) must be(
        List(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 6, 1), existing.firstTerm.effectiveFrom)
      )
      item.terms.map(_.amountMin) must be(List(Some(BigDecimal("7.00")), Some(BigDecimal("8.00")), None))
      response.data.find(_.id == sibling.id).value.terms.map(_.id) must be(List(sibling.firstTerm.id))
    }
  }
}
