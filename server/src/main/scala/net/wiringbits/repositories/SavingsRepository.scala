package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{ExpenseDAO, SavingsAllocationDAO, SavingsGoalDAO}
import net.wiringbits.repositories.models.{SavingsAllocation, SavingsGoal}
import play.api.db.Database

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class SavingsRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext, clock: Clock) {

  def createGoal(goal: SavingsGoal.Create): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SavingsGoalDAO.create(goal)
    }
  }

  def allGoals(userId: UUID): Future[List[(SavingsGoal, BigDecimal)]] = Future {
    database.withConnection { implicit conn =>
      SavingsGoalDAO.all(userId)
    }
  }

  def findGoal(id: UUID, userId: UUID): Future[Option[SavingsGoal]] = Future {
    database.withConnection { implicit conn =>
      SavingsGoalDAO.find(id, userId)
    }
  }

  def findGoalByName(userId: UUID, name: String): Future[Option[SavingsGoal]] = Future {
    database.withConnection { implicit conn =>
      SavingsGoalDAO.findByName(userId, name)
    }
  }

  def updateGoal(id: UUID, userId: UUID, name: String, targetAmount: Option[BigDecimal]): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SavingsGoalDAO.update(id, userId, name, targetAmount, clock.instant())
    }
  }

  def deleteGoal(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SavingsGoalDAO.delete(id, userId)
    }
  }

  def createAllocation(allocation: SavingsAllocation.Create): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SavingsAllocationDAO.create(allocation)
    }
  }

  def allAllocations(goalId: UUID, userId: UUID): Future[List[SavingsAllocation]] = Future {
    database.withConnection { implicit conn =>
      SavingsAllocationDAO.all(goalId, userId)
    }
  }

  def findAllocation(id: UUID, goalId: UUID, userId: UUID): Future[Option[SavingsAllocation]] = Future {
    database.withConnection { implicit conn =>
      SavingsAllocationDAO.find(id, goalId, userId)
    }
  }

  def updateAllocation(
      id: UUID,
      goalId: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: java.time.LocalDate,
      note: Option[String],
      merchantId: Option[UUID]
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SavingsAllocationDAO.update(id, goalId, userId, amount, date, note, merchantId, clock.instant())
    }
  }

  def deleteAllocation(id: UUID, goalId: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      SavingsAllocationDAO.delete(id, goalId, userId)
    }
  }

  def medianAmountByMerchant(userId: UUID, merchantId: UUID): Future[Option[BigDecimal]] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.medianAmountByMerchant(userId, merchantId)
    }
  }
}
