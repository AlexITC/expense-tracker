package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.RefundDAO
import net.wiringbits.repositories.models.Refund
import play.api.db.Database

import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class RefundsRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext, clock: Clock) {

  def create(refund: Refund.Create): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      RefundDAO.create(refund)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Refund]] = Future {
    database.withConnection { implicit conn =>
      RefundDAO.find(id, userId)
    }
  }

  def all(userId: UUID): Future[List[Refund]] = Future {
    database.withConnection { implicit conn =>
      RefundDAO.all(userId)
    }
  }

  def update(
      id: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      notes: Option[String]
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      RefundDAO.update(id, userId, amount, date, merchantId, categoryId, notes, clock.instant())
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      RefundDAO.delete(id, userId)
    }
  }

  def linkToExpense(id: UUID, userId: UUID, expenseId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      RefundDAO.setExpenseId(id, userId, expenseId, clock.instant())
    }
  }

  def unlinkFromExpense(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      RefundDAO.clearExpenseId(id, userId, clock.instant())
    }
  }
}
