package net.wiringbits.repositories

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.{
  SubscriptionCadence,
  SubscriptionPaymentMethod,
  SubscriptionTerm as ApiSubscriptionTerm
}
import net.wiringbits.api.utils.SubscriptionTerms
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{
  CategoryDAO,
  ExpenseDAO,
  MerchantDAO,
  SubscriptionDAO,
  SubscriptionExtraChargeDAO,
  SubscriptionTermDAO
}
import net.wiringbits.repositories.models.{Subscription, SubscriptionExtraCharge, SubscriptionTerm}
import net.wiringbits.repositories.support.ConstraintViolations
import play.api.db.Database

import java.sql.Connection
import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class SubscriptionsRepository @Inject() (database: Database)(implicit
    ec: DatabaseExecutionContext,
    clock: Clock
) {

  def create(request: Subscription.Create): Future[Unit] = Future {
    ConstraintViolations.translating(
      "subscription_user_id_name_unique" -> "A subscription with that name already exists"
    ) {
      database.withTransaction { implicit conn =>
        // Terms are read scoped by their own user_id, so a term owned by anyone else would be stored unnoticed.
        if (request.firstTerm.userId != request.userId || request.firstTerm.subscriptionId != request.id)
          throw new RuntimeException("The first term must belong to the subscription being created")
        if (request.categoryId.exists(cid => CategoryDAO.find(id = cid, userId = request.userId).isEmpty))
          throw new RuntimeException("Category not found")
        if (request.merchantId.exists(mid => MerchantDAO.find(id = mid, userId = request.userId).isEmpty))
          throw new RuntimeException("Merchant not found")
        SubscriptionDAO.create(request)
        SubscriptionTermDAO.create(request.firstTerm)
      }
    }
  }

  def all(userId: UUID): Future[List[Subscription.WithDetails]] = Future {
    database.withTransaction { implicit conn =>
      val subscriptions = SubscriptionDAO.all(userId)
      val termsBySubscription = SubscriptionTermDAO.allForUser(userId).groupBy(_.subscriptionId)
      val extrasBySubscription = SubscriptionExtraChargeDAO.allForUser(userId).groupBy(_.subscriptionId)
      subscriptions.map { s =>
        Subscription.WithDetails(
          subscription = s,
          terms = termsBySubscription.getOrElse(s.id, List.empty),
          extraCharges = extrasBySubscription.getOrElse(s.id, List.empty)
        )
      }
    }
  }

  def terms(subscriptionId: UUID, userId: UUID): Future[List[SubscriptionTerm]] = Future {
    database.withConnection { implicit conn =>
      SubscriptionTermDAO.forSubscription(subscriptionId = subscriptionId, userId = userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Subscription]] = Future {
    database.withConnection { implicit conn =>
      SubscriptionDAO.find(id = id, userId = userId)
    }
  }

  def count(userId: UUID): Future[Long] = Future {
    database.withConnection { implicit conn =>
      SubscriptionDAO.count(userId)
    }
  }

  def update(
      id: UUID,
      userId: UUID,
      name: String,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      merchantId: Option[UUID],
      description: Option[String]
  ): Future[Unit] = Future {
    ConstraintViolations.translating(
      "subscription_user_id_name_unique" -> "A subscription with that name already exists"
    ) {
      database.withTransaction { implicit conn =>
        if (categoryId.exists(cid => CategoryDAO.find(id = cid, userId = userId).isEmpty))
          throw new RuntimeException("Category not found")
        if (merchantId.exists(mid => MerchantDAO.find(id = mid, userId = userId).isEmpty))
          throw new RuntimeException("Merchant not found")
        SubscriptionDAO.update(
          id = id,
          userId = userId,
          name = name,
          cadence = cadence,
          intervalDays = intervalDays,
          intervalMonths = intervalMonths,
          paymentMethod = paymentMethod,
          categoryId = categoryId,
          merchantId = merchantId,
          description = description,
          updatedAt = clock.instant()
        )
      }
    }
  }

  def changePrice(
      id: UUID,
      userId: UUID,
      newTermId: UUID,
      effectiveFrom: LocalDate,
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal]
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      changeTerms(id = id, userId = userId) { terms =>
        SubscriptionTerms.priceChange(
          terms,
          newTermId = newTermId,
          effectiveFrom = effectiveFrom,
          amountMin = amountMin,
          amountMax = amountMax
        )
      }
    }
  }

  def cancel(id: UUID, userId: UUID, effectiveTo: LocalDate): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      changeTerms(id = id, userId = userId)(terms => SubscriptionTerms.cancel(terms, effectiveTo = effectiveTo))
    }
  }

  def resume(
      id: UUID,
      userId: UUID,
      newTermId: UUID,
      effectiveFrom: LocalDate,
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal]
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      changeTerms(id = id, userId = userId) { terms =>
        SubscriptionTerms.resume(
          terms,
          newTermId = newTermId,
          effectiveFrom = effectiveFrom,
          amountMin = amountMin,
          amountMax = amountMax
        )
      }
    }
  }

  // Plans against the terms read under the subscription row lock, so concurrent changes cannot interleave.
  private def changeTerms(id: UUID, userId: UUID)(
      plan: List[ApiSubscriptionTerm] => Either[String, SubscriptionTerms.Plan]
  )(implicit conn: Connection): Unit = {
    if (SubscriptionDAO.lockForUpdate(id = id, userId = userId).isEmpty)
      throw new RuntimeException("Subscription not found")
    val terms = SubscriptionTermDAO
      .forSubscription(subscriptionId = id, userId = userId)
      .map(_.transformInto[ApiSubscriptionTerm])
    val planned = plan(terms).fold(error => throw new RuntimeException(error), identity)
    val now = clock.instant()
    planned.close.foreach { close =>
      SubscriptionTermDAO.close(
        termId = close.termId,
        subscriptionId = id,
        userId = userId,
        effectiveTo = close.effectiveTo,
        updatedAt = now
      )
    }
    planned.open.foreach { term =>
      SubscriptionTermDAO.create(
        term
          .into[SubscriptionTerm]
          .withFieldConst(_.userId, userId)
          .withFieldConst(_.subscriptionId, id)
          .withFieldConst(_.createdAt, now)
          .withFieldConst(_.updatedAt, now)
          .transform
      )
    }
    SubscriptionDAO.touch(id = id, userId = userId, updatedAt = now)
  }

  def earliestExpenseDateForMerchant(userId: UUID, merchantId: UUID): Future[Option[LocalDate]] = Future {
    database.withTransaction { implicit conn =>
      if (MerchantDAO.find(id = merchantId, userId = userId).isEmpty)
        throw new RuntimeException("Merchant not found")
      ExpenseDAO.earliestDateForMerchant(userId = userId, merchantId = merchantId)
    }
  }

  // Counts under the subscription row lock, so concurrent adds cannot exceed the cap.
  def addExtraCharge(charge: SubscriptionExtraCharge, maxPerSubscription: Int): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      if (SubscriptionDAO.lockForUpdate(id = charge.subscriptionId, userId = charge.userId).isEmpty)
        throw new RuntimeException("Subscription not found")
      val count = SubscriptionExtraChargeDAO.countForSubscription(
        subscriptionId = charge.subscriptionId,
        userId = charge.userId
      )
      if (count >= maxPerSubscription)
        throw new RuntimeException(s"Maximum number of extra charges ($maxPerSubscription) reached")
      SubscriptionExtraChargeDAO.create(charge)
    }
  }

  def deleteExtraCharge(id: UUID, subscriptionId: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SubscriptionExtraChargeDAO.delete(id = id, subscriptionId = subscriptionId, userId = userId)
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SubscriptionDAO.delete(id = id, userId = userId)
    }
  }
}
