package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{ExpenseCategorySplitDAO, ExpenseDAO}
import net.wiringbits.repositories.models.{Expense, ExpenseCategorySplit}
import play.api.db.Database

import java.time.{Clock, LocalDate, LocalTime}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class ExpensesRepository @Inject() (database: Database)(implicit
    ec: DatabaseExecutionContext,
    clock: Clock
) {

  def create(expense: Expense.Create, splits: List[ExpenseCategorySplit.Create]): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      ExpenseDAO.create(expense)
      ExpenseCategorySplitDAO.createAll(splits)
    }
  }

  def all(
      userId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate],
      unlinkedOnly: Boolean
  ): Future[List[Expense]] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.all(userId, from, to, unlinkedOnly)
    }
  }

  def allSplits(userId: UUID): Future[List[ExpenseCategorySplit]] = Future {
    database.withConnection { implicit conn =>
      ExpenseCategorySplitDAO.findByUserId(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Expense]] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.find(id, userId)
    }
  }

  def update(
      id: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      time: Option[LocalTime],
      merchantId: Option[UUID],
      notes: Option[String],
      splits: List[ExpenseCategorySplit.Create]
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      ExpenseDAO.update(id, userId, amount, date, time, merchantId, notes, clock.instant())
      ExpenseCategorySplitDAO.deleteByExpenseId(id, userId)
      ExpenseCategorySplitDAO.createAll(splits)
    }
  }

  def countByMonth(userId: UUID, year: Int, month: Int): Future[Long] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.countByMonth(userId, year, month)
    }
  }

  def findSplitsByExpenseId(expenseId: UUID, userId: UUID): Future[List[ExpenseCategorySplit]] = Future {
    database.withConnection { implicit conn =>
      ExpenseCategorySplitDAO.findByExpenseId(expenseId, userId)
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.delete(id, userId)
    }
  }
}
