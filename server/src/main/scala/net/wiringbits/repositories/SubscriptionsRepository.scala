package net.wiringbits.repositories

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{CategoryDAO, SubscriptionDAO}
import net.wiringbits.repositories.models.Subscription
import net.wiringbits.repositories.support.ConstraintViolations
import play.api.db.Database

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
        if (request.categoryId.exists(cid => CategoryDAO.find(id = cid, userId = request.userId).isEmpty))
          throw new RuntimeException("Category not found")
        SubscriptionDAO.create(request)
      }
    }
  }

  def all(userId: UUID): Future[List[Subscription]] = Future {
    database.withConnection { implicit conn =>
      SubscriptionDAO.all(userId)
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
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      startDate: LocalDate
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
          amountMin = amountMin,
          amountMax = amountMax,
          paymentMethod = paymentMethod,
          categoryId = categoryId,
          startDate = startDate,
          updatedAt = clock.instant()
        )
      }
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SubscriptionDAO.delete(id = id, userId = userId)
    }
  }
}
