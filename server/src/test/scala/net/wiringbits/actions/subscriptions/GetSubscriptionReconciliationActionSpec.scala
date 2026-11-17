package net.wiringbits.actions.subscriptions

import net.wiringbits.api.models.subscriptions.{
  GetSubscriptionReconciliation,
  SubscriptionCharge,
  SubscriptionChargeSource,
  SubscriptionCycleStatus
}
import net.wiringbits.config.SubscriptionsConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.models.SubscriptionManualPayment
import org.scalatest.LoneElement.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate, ZoneOffset}
import java.util.UUID

class GetSubscriptionReconciliationActionSpec extends RepositorySpec with RepositoryUtils {

  // With a 5-day tolerance, a monthly subscription starting on March 10 has the cycles March 10 (window closed),
  // April 10 (window closed on April 15) and May 10 (upcoming).
  private val today = LocalDate.of(2025, 4, 20)
  private val startDate = LocalDate.of(2025, 3, 10)
  private val price = BigDecimal("15.00")

  private def newAction()(using repositories: RepositoryComponents): GetSubscriptionReconciliationAction =
    new GetSubscriptionReconciliationAction(
      repositories.subscriptions,
      SubscriptionsConfig(maxPerUser = 200, reconciliationToleranceDays = 5)
    )(using executionContext, Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant, ZoneOffset.UTC))

  private def uniqueName(prefix: String): String = s"$prefix ${UUID.randomUUID()}"

  private def createGym(userId: UUID)(using repositories: RepositoryComponents): UUID =
    createSubscription(
      userId,
      name = uniqueName("Gym"),
      amountMin = Some(price),
      amountMax = Some(price),
      startDate = startDate
    ).futureValue.id

  private def statuses(
      response: GetSubscriptionReconciliation.Response,
      subscriptionId: UUID
  ): List[(LocalDate, SubscriptionCycleStatus)] =
    response.data.find(_.subscriptionId == subscriptionId).toList.flatMap(_.cycles.map(c => c.dueDate -> c.status))

  private def firstStatus(response: GetSubscriptionReconciliation.Response, subscriptionId: UUID) =
    statuses(response, subscriptionId).headOption.map(_._2)

  private def matched(source: SubscriptionChargeSource, id: UUID, date: LocalDate, late: Boolean) =
    SubscriptionCycleStatus.Matched(SubscriptionCharge(source, id, date, price), late = late)

  "apply" should {
    "match an expense to its cycle" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val expense = createExpense(user.id, amount = price, date = startDate).futureValue

      val response = newAction().apply(user.id, None).futureValue

      statuses(response, subscriptionId) must be(
        List(
          startDate -> matched(SubscriptionChargeSource.Expense, expense.id, startDate, late = false),
          LocalDate.of(2025, 4, 10) -> SubscriptionCycleStatus.Missing,
          LocalDate.of(2025, 5, 10) -> SubscriptionCycleStatus.Upcoming
        )
      )
    }

    "match an unlinked debit bank statement row" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val (_, rows) = createBankStatementWithRows(user.id, List(startDate -> -price)).futureValue

      val response = newAction().apply(user.id, None).futureValue

      firstStatus(response, subscriptionId) must be(
        Some(matched(SubscriptionChargeSource.BankStatementRow, rows.loneElement.id, startDate, late = false))
      )
    }

    "not count a row linked to an expense on top of the expense" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val expense = createExpense(user.id, amount = price, date = startDate).futureValue
      val (_, rows) = createBankStatementWithRows(user.id, List(startDate -> -price)).futureValue
      linkStatementRowToExpense(rows.loneElement.id, user.id, expense.id).futureValue

      val response = newAction().apply(user.id, None).futureValue

      firstStatus(response, subscriptionId) must be(
        Some(matched(SubscriptionChargeSource.Expense, expense.id, startDate, late = false))
      )
    }

    "not read credit rows as candidates" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createGym(user.id)
      createBankStatementWithRows(user.id, List(startDate -> price)).futureValue

      val data = repositories.subscriptions
        .reconciliationData(userId = user.id, lookbackDays = 5, to = today.plusDays(5))
        .futureValue

      data.bankStatementRows must be(List.empty)
    }

    "not match an ignored debit row" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val (statement, rows) = createBankStatementWithRows(user.id, List(startDate -> -price)).futureValue
      ignoreStatementRow(rows.loneElement.id, statement.id).futureValue

      val response = newAction().apply(user.id, None).futureValue

      firstStatus(response, subscriptionId) must be(Some(SubscriptionCycleStatus.Missing))
    }

    "match charges on the first day of the lookback window" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val early = startDate.minusDays(5)
      val expense = createExpense(user.id, amount = price, date = early).futureValue
      val (_, rows) = createBankStatementWithRows(user.id, List(early -> -price)).futureValue

      val response = newAction().apply(user.id, None).futureValue

      firstStatus(response, subscriptionId) must be(
        Some(
          SubscriptionCycleStatus.Retried(
            List(
              SubscriptionCharge(SubscriptionChargeSource.Expense, expense.id, early, price),
              SubscriptionCharge(SubscriptionChargeSource.BankStatementRow, rows.loneElement.id, early, price)
            ).sortBy(_.id.toString)
          )
        )
      )
    }

    "read charges only within the lookback and look-ahead bounds" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createGym(user.id)
      val dates = List(startDate.minusDays(6), startDate.minusDays(5), today.plusDays(5), today.plusDays(6))
      val expenses = dates.map(d => createExpense(user.id, amount = price, date = d).futureValue)
      val (_, rows) = createBankStatementWithRows(user.id, dates.map(_ -> -price)).futureValue

      val data = repositories.subscriptions
        .reconciliationData(userId = user.id, lookbackDays = 5, to = today.plusDays(5))
        .futureValue

      data.expenses.map(_.id) must be(List(expenses(1).id, expenses(2).id))
      data.bankStatementRows.map(_.id) must be(List(rows(1).id, rows(2).id))
    }

    "return no subscriptions for a user without any" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      newAction().apply(user.id, None).futureValue must be(GetSubscriptionReconciliation.Response(Nil))
    }

    "flag a charge after the due date as late" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val paidOn = startDate.plusDays(3)
      val expense = createExpense(user.id, amount = price, date = paidOn).futureValue

      val response = newAction().apply(user.id, None).futureValue

      firstStatus(response, subscriptionId) must be(
        Some(matched(SubscriptionChargeSource.Expense, expense.id, paidOn, late = true))
      )
    }

    "report several charges for one cycle as retried" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val first = createExpense(user.id, amount = price, date = startDate).futureValue
      val retryDate = startDate.plusDays(2)
      val (_, rows) = createBankStatementWithRows(user.id, List(retryDate -> -price)).futureValue

      val response = newAction().apply(user.id, None).futureValue

      firstStatus(response, subscriptionId) must be(
        Some(
          SubscriptionCycleStatus.Retried(
            List(
              SubscriptionCharge(SubscriptionChargeSource.Expense, first.id, startDate, price),
              SubscriptionCharge(SubscriptionChargeSource.BankStatementRow, rows.loneElement.id, retryDate, price)
            )
          )
        )
      )
    }

    "credit a manual payment to its cycle" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val subscriptionId = createGym(user.id)
      val now = Instant.now()
      val payment = SubscriptionManualPayment(
        id = UUID.randomUUID(),
        userId = user.id,
        subscriptionId = subscriptionId,
        paidDate = startDate,
        amount = price,
        note = None,
        createdAt = now,
        updatedAt = now
      )
      repositories.subscriptions.addManualPayment(payment, maxPerSubscription = 500).futureValue

      val response = newAction().apply(user.id, None).futureValue

      firstStatus(response, subscriptionId) must be(
        Some(matched(SubscriptionChargeSource.ManualPayment, payment.id, startDate, late = false))
      )
      response.data.find(_.subscriptionId == subscriptionId).map(_.manualPayments.map(_.id)) must be(
        Some(List(payment.id))
      )
    }

    "return only the requested subscription" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val requested = createGym(user.id)
      val other = createGym(user.id)
      val expense = createExpense(user.id, amount = price, date = startDate).futureValue

      val all = newAction().apply(user.id, None).futureValue
      val single = newAction().apply(user.id, Some(requested)).futureValue

      all.data.map(_.subscriptionId).toSet must be(Set(requested, other))
      single.data.loneElement must be(all.data.find(_.subscriptionId == requested).get)
      // The charge is credited the same way whichever subscription is requested.
      val creditedTo = all.data.filter(_.cycles.exists(_.status.isInstanceOf[SubscriptionCycleStatus.Matched]))
      creditedTo.loneElement.cycles.head.status must be(
        matched(SubscriptionChargeSource.Expense, expense.id, startDate, late = false)
      )
    }

    "never see another user's subscriptions, expenses or rows" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val subscriptionId = createGym(user.id)
      createGym(other.id)
      createExpense(other.id, amount = price, date = startDate).futureValue
      createBankStatementWithRows(other.id, List(startDate -> -price)).futureValue

      val response = newAction().apply(user.id, None).futureValue

      response.data.map(_.subscriptionId) must be(List(subscriptionId))
      statuses(response, subscriptionId).map(_._2) must be(
        List(SubscriptionCycleStatus.Missing, SubscriptionCycleStatus.Missing, SubscriptionCycleStatus.Upcoming)
      )
    }

    "fail for another user's subscription id" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      createGym(user.id)
      val otherSubscriptionId = createGym(other.id)

      val error = newAction().apply(user.id, Some(otherSubscriptionId)).failed.futureValue
      error.getMessage must be("Subscription not found")
    }

    "fail for an unknown subscription id" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createGym(user.id)

      val error = newAction().apply(user.id, Some(UUID.randomUUID())).failed.futureValue
      error.getMessage must be("Subscription not found")
    }
  }
}
