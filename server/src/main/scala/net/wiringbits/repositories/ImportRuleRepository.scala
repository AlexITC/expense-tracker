package net.wiringbits.repositories

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{CategoryDAO, ImportRuleDAO, MerchantDAO}
import net.wiringbits.repositories.models.ImportRule
import play.api.db.Database

import java.sql.Connection
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class ImportRuleRepository @Inject() (database: Database)(implicit
    ec: DatabaseExecutionContext,
    clock: Clock
) {

  private val conflictMappings: Seq[(String, String)] = Seq(
    "merchant_rule_ignore_unique" -> "An ignore rule with that pattern already exists",
    "merchant_rule_set_merchant_unique" -> "A rule with that pattern and merchant already exists",
    "merchant_rule_set_category_unique" -> "A rule with that pattern and category already exists"
  )

  def create(
      userId: UUID,
      action: ImportRuleAction,
      pattern: String,
      autoCreated: Boolean = false
  ): Future[UUID] = Future {
    ConstraintViolations.translating(conflictMappings*) {
      database.withTransaction { implicit conn =>
        verifyReferencesOwned(action, userId)
        val now = clock.instant()
        val rule = ImportRule.Create(
          id = UuidCreator.getTimeOrderedEpoch(),
          userId = userId,
          action = action,
          pattern = pattern,
          autoCreated = autoCreated,
          createdAt = now,
          updatedAt = now
        )
        ImportRuleDAO.create(rule)
        rule.id
      }
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

  // Absorbs an existing set_merchant rule for the same pattern/merchant (get-or-create, not a reject).
  def createSetMerchantIfAbsent(userId: UUID, merchantId: UUID, pattern: String): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val action = ImportRuleAction.SetMerchant(merchantId)
      verifyReferencesOwned(action, userId)
      val now = clock.instant()
      val rule = ImportRule.Create(
        id = UuidCreator.getTimeOrderedEpoch(),
        userId = userId,
        action = action,
        pattern = pattern,
        autoCreated = true,
        createdAt = now,
        updatedAt = now
      )
      ImportRuleDAO.createSetMerchantIfAbsent(rule)
    }
  }

  def update(
      id: UUID,
      userId: UUID,
      action: ImportRuleAction,
      pattern: String
  ): Future[Unit] =
    Future {
      ConstraintViolations.translating(conflictMappings*) {
        database.withTransaction { implicit conn =>
          if (ImportRuleDAO.find(id, userId).isEmpty) throw new RuntimeException("Import rule not found")
          verifyReferencesOwned(action, userId)
          ImportRuleDAO.update(id, userId, action, pattern, clock.instant())
        }
      }
    }

  // Tenant/ownership integrity for the rule's referenced entity, enforced within the write transaction so a
  // cross-tenant reference can never be persisted (see architecture / persistence reviewers).
  private def verifyReferencesOwned(action: ImportRuleAction, userId: UUID)(implicit conn: Connection): Unit =
    action match {
      case ImportRuleAction.SetMerchant(merchantId) =>
        if (MerchantDAO.find(merchantId, userId).isEmpty) throw new RuntimeException("Merchant not found")
      case ImportRuleAction.SetCategory(categoryId) =>
        if (CategoryDAO.find(categoryId, userId).isEmpty) throw new RuntimeException("Category not found")
      case ImportRuleAction.Ignore => ()
    }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      ImportRuleDAO.delete(id, userId)
    }
  }
}
