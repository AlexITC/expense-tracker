package net.wiringbits.repositories

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.ImportRuleDAO
import net.wiringbits.repositories.models.ImportRule
import play.api.db.Database

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class ImportRuleRepository @Inject() (database: Database)(implicit
    ec: DatabaseExecutionContext,
    clock: Clock
) {

  def create(
      userId: UUID,
      action: ImportRuleAction,
      pattern: String,
      merchantId: Option[UUID],
      categoryId: Option[UUID] = None,
      autoCreated: Boolean = false
  ): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      val now = clock.instant()
      val rule = ImportRule.Create(
        id = UuidCreator.getTimeOrderedEpoch(),
        userId = userId,
        action = action,
        pattern = pattern,
        merchantId = merchantId,
        categoryId = categoryId,
        autoCreated = autoCreated,
        createdAt = now,
        updatedAt = now
      )
      ImportRuleDAO.create(rule)
      rule.id
    }
  }

  def all(userId: UUID): Future[List[ImportRule]] = Future {
    database.withConnection { implicit conn =>
      ImportRuleDAO.all(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[ImportRule]] = Future {
    database.withConnection { implicit conn =>
      ImportRuleDAO.find(id, userId)
    }
  }

  def existsByUserPatternIgnore(userId: UUID, pattern: String, excludeId: Option[UUID] = None): Future[Boolean] =
    Future {
      database.withConnection { implicit conn =>
        ImportRuleDAO.existsByUserPatternIgnore(userId, pattern, excludeId)
      }
    }

  def existsByUserPatternMerchant(
      userId: UUID,
      pattern: String,
      merchantId: UUID,
      excludeId: Option[UUID] = None
  ): Future[Boolean] = Future {
    database.withConnection { implicit conn =>
      ImportRuleDAO.existsByUserPatternMerchant(userId, pattern, merchantId, excludeId)
    }
  }

  def update(
      id: UUID,
      userId: UUID,
      action: ImportRuleAction,
      pattern: String,
      merchantId: Option[UUID],
      categoryId: Option[UUID]
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      ImportRuleDAO.update(id, userId, action, pattern, merchantId, categoryId, clock.instant())
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      ImportRuleDAO.delete(id, userId)
    }
  }
}
