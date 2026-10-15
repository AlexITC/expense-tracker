package net.wiringbits.actions.refunds

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.refunds.CreateRefund
import net.wiringbits.repositories.{CategoriesRepository, ExpensesRepository, MerchantsRepository, RefundsRepository}
import net.wiringbits.repositories.models.Refund
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateRefundAction @Inject() (
    refundsRepository: RefundsRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository,
    expensesRepository: ExpensesRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, request: CreateRefund.Request): Future[CreateRefund.Response] = {
    logger.info(
      s"Create refund: userId=$userId amount=${request.amount} date=${request.date} merchantId=${request.merchantId}"
    )
    for {
      _ <- validate(userId, request)
      now = clock.instant()
      refundId = UuidCreator.getTimeOrderedEpoch()
      refund = Refund.Create(
        id = refundId,
        userId = userId,
        amount = request.amount,
        date = request.date,
        merchantId = request.merchantId,
        categoryId = request.categoryId,
        expenseId = request.expenseId,
        notes = request.notes,
        createdAt = now,
        updatedAt = now
      )
      _ <- refundsRepository.create(refund)
    } yield CreateRefund.Response(id = refundId, amount = request.amount, date = request.date)
  }

  private def validate(userId: UUID, request: CreateRefund.Request): Future[Unit] = {
    if (request.amount <= 0)
      Future.failed(new RuntimeException("Amount must be greater than 0"))
    else if (request.notes.exists(_.length > 255))
      Future.failed(new RuntimeException("Notes must be 255 characters or less"))
    else
      for {
        _ <- request.merchantId match {
          case None => Future.unit
          case Some(mId) =>
            merchantsRepository.find(mId, userId).flatMap {
              case None => Future.failed(new RuntimeException("Merchant not found"))
              case Some(_) => Future.unit
            }
        }
        _ <- request.categoryId match {
          case None => Future.unit
          case Some(cId) =>
            categoriesRepository.find(cId, userId).flatMap {
              case None => Future.failed(new RuntimeException("Category not found"))
              case Some(_) => Future.unit
            }
        }
        _ <- request.expenseId match {
          case None => Future.unit
          case Some(eId) =>
            expensesRepository.find(eId, userId).flatMap {
              case None => Future.failed(new RuntimeException("Expense not found"))
              case Some(_) => Future.unit
            }
        }
      } yield ()
  }
}
