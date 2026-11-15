package net.wiringbits.repositories

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.subscriptions.{
  SubscriptionCadence,
  SubscriptionPaymentMethod,
  SubscriptionTerm as ApiSubscriptionTerm
}
import net.wiringbits.api.utils.SubscriptionTerms
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{CategoryDAO, SubscriptionDAO, SubscriptionTermDAO}
import net.wiringbits.repositories.models.{Subscription, SubscriptionTerm}
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
        SubscriptionDAO.create(request)
        SubscriptionTermDAO.create(request.firstTerm)
      }
    }
  }

  def all(userId: UUID): Future[List[Subscription.WithTerms]] = Future {
    database.withTransaction { implicit conn =>
      val subscriptions = SubscriptionDAO.all(userId)
      val termsBySubscription = SubscriptionTermDAO.allForUser(userId).groupBy(_.subscriptionId)
      subscriptions.map(s => Subscription.WithTerms(s, termsBySubscription.getOrElse(s.id, List.empty)))
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
      description: Option[String]
  ): Future[Unit] = Future {
    ConstraintViolations.translating(
      "subscription_user_id_name_unique" -> "A subscription with that name already exists"
    ) {
      database.withTransaction { implicit conn =>
        if (categoryId.exists(cid => CategoryDAO.find(id = cid, userId = userId).isEmpty))
          throw new RuntimeException("Category not found")
        SubscriptionDAO.update(
          id = id,
          userId = userId,
          name = name,
          cadence = cadence,
          intervalDays = intervalDays,
          intervalMonths = intervalMonths,
          paymentMethod = paymentMethod,
          categoryId = categoryId,
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

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SubscriptionDAO.delete(id = id, userId = userId)
    }
  }
}
