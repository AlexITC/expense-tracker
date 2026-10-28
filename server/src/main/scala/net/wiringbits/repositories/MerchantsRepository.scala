package net.wiringbits.repositories

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{CategoryDAO, ExpenseDAO, MerchantDAO, RefundDAO}
import net.wiringbits.repositories.models.Merchant
import play.api.db.Database

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class MerchantsRepository @Inject() (database: Database)(implicit
    ec: DatabaseExecutionContext,
    clock: Clock
) {

  def count(userId: UUID): Future[Long] = Future {
    database.withConnection { implicit conn =>
      MerchantDAO.count(userId)
    }
  }

  def create(request: Merchant.Create): Future[Unit] = Future {
    ConstraintViolations.translating(
      "merchant_user_id_name_unique" -> "A merchant with that name already exists",
      "merchant_tax_id_user_idx" -> "A merchant with that tax ID already exists"
    ) {
      database.withTransaction { implicit conn =>
        if (request.categoryId.exists(cid => CategoryDAO.find(cid, request.userId).isEmpty))
          throw new RuntimeException("Category not found")
        MerchantDAO.create(request)
      }
    }
  }

  def createPendingIfAbsent(userId: UUID, name: String): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      MerchantDAO.findByName(userId, name) match {
        case Some(existing) => existing.id
        case None =>
          val id = UuidCreator.getTimeOrderedEpoch()
          val now = clock.instant()
          MerchantDAO.create(
            Merchant.Create(
              id = id,
              userId = userId,
              name = name,
              notes = None,
              categoryId = None,
              taxId = None,
              reviewed = false,
              createdAt = now,
              updatedAt = now
            )
          )
          id
      }
    }
  }

  def all(userId: UUID): Future[List[Merchant]] = Future {
    database.withConnection { implicit conn =>
      MerchantDAO.all(userId)
    }
  }

  def findPending(userId: UUID): Future[List[Merchant]] = Future {
    database.withConnection { implicit conn =>
      MerchantDAO.findPending(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Merchant]] = Future {
    database.withConnection { implicit conn =>
      MerchantDAO.find(id, userId)
    }
  }

  def findByName(userId: UUID, name: String): Future[Option[Merchant]] = Future {
    database.withConnection { implicit conn =>
      MerchantDAO.findByName(userId, name)
    }
  }

  def findByTaxId(userId: UUID, taxId: String): Future[Option[Merchant]] = Future {
    database.withConnection { implicit conn =>
      MerchantDAO.findByTaxId(userId, taxId)
    }
  }

  def update(
      id: UUID,
      userId: UUID,
      name: String,
      notes: Option[String],
      categoryId: Option[UUID],
      taxId: Option[String]
  ): Future[Unit] = Future {
    ConstraintViolations.translating(
      "merchant_user_id_name_unique" -> "A merchant with that name already exists",
      "merchant_tax_id_user_idx" -> "A merchant with that tax ID already exists"
    ) {
      database.withTransaction { implicit conn =>
        if (MerchantDAO.find(id, userId).isEmpty) throw new RuntimeException("Merchant not found")
        if (categoryId.exists(cid => CategoryDAO.find(cid, userId).isEmpty))
          throw new RuntimeException("Category not found")
        MerchantDAO.update(id, userId, name, notes, categoryId, taxId, clock.instant())
      }
    }
  }

  def markReviewed(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      MerchantDAO.markReviewed(id, userId, clock.instant())
    }
  }

  def rePointAndDelete(pendingId: UUID, realId: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val now = clock.instant()
      ExpenseDAO.updateMerchantId(pendingId, realId, userId, now)
      RefundDAO.updateMerchantId(pendingId, realId, userId, now)
      MerchantDAO.findAndDeletePending(pendingId, userId)
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      MerchantDAO.delete(id, userId)
    }
  }
}
