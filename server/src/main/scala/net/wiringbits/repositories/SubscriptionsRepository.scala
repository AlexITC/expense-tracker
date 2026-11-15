package net.wiringbits.repositories

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{CategoryDAO, SubscriptionDAO, SubscriptionTermDAO}
import net.wiringbits.repositories.models.{Subscription, SubscriptionTerm}
import net.wiringbits.repositories.support.ConstraintViolations
import play.api.db.Database

import java.time.Clock
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
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID]
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
          paymentMethod = paymentMethod,
          categoryId = categoryId,
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
