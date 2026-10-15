package net.wiringbits.actions.refunds

import net.wiringbits.api.models.refunds.UpdateRefund
import net.wiringbits.repositories.{CategoriesRepository, MerchantsRepository, RefundsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateRefundAction @Inject() (
    refundsRepository: RefundsRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {

  def apply(refundId: UUID, userId: UUID, request: UpdateRefund.Request): Future[UpdateRefund.Response] =
    for {
      _ <- validate(userId, request)
      refundOpt <- refundsRepository.find(refundId, userId)
      _ = if (refundOpt.isEmpty) throw new RuntimeException("Refund not found")
      _ <- refundsRepository.update(
        refundId,
        userId,
        request.amount,
        request.date,
        request.merchantId,
        request.categoryId,
        request.notes
      )
    } yield UpdateRefund.Response()

  private def validate(userId: UUID, request: UpdateRefund.Request): Future[Unit] = {
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
      } yield ()
  }
}
