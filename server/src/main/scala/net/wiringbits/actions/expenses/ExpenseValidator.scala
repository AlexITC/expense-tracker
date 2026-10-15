package net.wiringbits.actions.expenses

import net.wiringbits.repositories.{CategoriesRepository, MerchantsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ExpenseValidator @Inject() (
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {

  def validate(
      userId: UUID,
      amount: BigDecimal,
      notes: Option[String],
      merchantId: Option[UUID],
      splits: List[(UUID, BigDecimal)]
  ): Future[Unit] = {
    if (amount <= 0)
      Future.failed(new RuntimeException("Amount must be greater than 0"))
    else if (notes.exists(_.length > 255))
      Future.failed(new RuntimeException("Notes must be 255 characters or less"))
    else {
      val duplicates = splits.map(_._1).groupBy(identity).filter(_._2.size > 1)
      if (duplicates.nonEmpty)
        Future.failed(new RuntimeException("Duplicate category in splits"))
      else if (splits.exists(_._2 <= 0))
        Future.failed(new RuntimeException("Split amount must be greater than 0"))
      else if (splits.map(_._2).sum > amount)
        Future.failed(new RuntimeException("Sum of split amounts exceeds expense amount"))
      else
        for {
          _ <- merchantId match {
            case None => Future.unit
            case Some(mId) =>
              merchantsRepository.find(mId, userId).flatMap {
                case None => Future.failed(new RuntimeException("Merchant not found"))
                case Some(_) => Future.unit
              }
          }
          _ <- Future.traverse(splits) { case (categoryId, _) =>
            categoriesRepository.find(categoryId, userId).flatMap {
              case None => Future.failed(new RuntimeException("Category not found"))
              case Some(_) => Future.unit
            }
          }
        } yield ()
    }
  }
}
