package net.wiringbits.actions.subscriptions

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.{
  GetSubscriptionReconciliation,
  SubscriptionChargeSource,
  SubscriptionManualPayment as ApiSubscriptionManualPayment,
  SubscriptionTerm as ApiSubscriptionTerm
}
import net.wiringbits.api.utils.{ReconcilerInput, SubscriptionReconciler}
import net.wiringbits.config.SubscriptionsConfig
import net.wiringbits.repositories.SubscriptionsRepository
import net.wiringbits.repositories.models.SubscriptionReconciliationData

import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetSubscriptionReconciliationAction @Inject() (
    subscriptionsRepository: SubscriptionsRepository,
    subscriptionsConfig: SubscriptionsConfig
)(implicit ec: ExecutionContext, clock: Clock) {

  // Every subscription of the user is reconciled, even when only one is requested, so a charge that could pay several
  // subscriptions is always credited to the same one.
  def apply(userId: UUID, subscriptionId: Option[UUID]): Future[GetSubscriptionReconciliation.Response] = {
    val today = LocalDate.now(clock)
    val toleranceDays = subscriptionsConfig.reconciliationToleranceDays
    for {
      data <- subscriptionsRepository.reconciliationData(
        userId = userId,
        lookbackDays = toleranceDays,
        to = today.plusDays(toleranceDays.toLong)
      )
      reconciled <- SubscriptionReconciler
        .reconcile(
          subscriptions = data.subscriptions.map(toSubscription),
          manualPayments = data.manualPayments.map { p =>
            ReconcilerInput.ManualPayment(p.subscriptionId, p.transformInto[ApiSubscriptionManualPayment])
          },
          candidates = toCandidates(data),
          today = today,
          toleranceDays = toleranceDays
        )
        .fold(error => Future.failed(new RuntimeException(error.message)), Future.successful)
      selected <- subscriptionId.fold(Future.successful(reconciled)) { id =>
        reconciled.filter(_.subscriptionId == id) match {
          case Nil => Future.failed(new RuntimeException("Subscription not found"))
          case found => Future.successful(found)
        }
      }
    } yield GetSubscriptionReconciliation.Response(selected)
  }

  private def toSubscription(row: SubscriptionReconciliationData.SubscriptionWithTerms): ReconcilerInput.Subscription =
    row.subscription
      .into[ReconcilerInput.Subscription]
      .withFieldConst(_.terms, row.terms.map(_.transformInto[ApiSubscriptionTerm]))
      .transform

  private def toCandidates(data: SubscriptionReconciliationData): List[ReconcilerInput.Charge] =
    data.expenses.map(
      _.into[ReconcilerInput.Charge].withFieldConst(_.source, SubscriptionChargeSource.Expense).transform
    ) ++ data.bankStatementRows.map(
      _.into[ReconcilerInput.Charge]
        .withFieldConst(_.source, SubscriptionChargeSource.BankStatementRow)
        .withFieldConst(_.merchantId, None)
        .transform
    )
}
