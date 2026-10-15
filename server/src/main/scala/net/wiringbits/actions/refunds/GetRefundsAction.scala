package net.wiringbits.actions.refunds

import net.wiringbits.api.models.refunds.GetRefunds
import net.wiringbits.repositories.{CategoriesRepository, MerchantsRepository, RefundsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetRefundsAction @Inject() (
    refundsRepository: RefundsRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetRefunds.Response] =
    for {
      refunds <- refundsRepository.all(userId)
      merchants <- merchantsRepository.all(userId)
      categories <- categoriesRepository.all(userId)
    } yield {
      val merchantById = merchants.map(m => m.id -> m.name).toMap
      val categoryById = categories.map(c => c.id -> c.name).toMap
      val data = refunds.map { r =>
        GetRefunds.Response.Refund(
          id = r.id,
          amount = r.amount,
          date = r.date,
          merchantId = r.merchantId,
          merchantName = r.merchantId.flatMap(merchantById.get),
          categoryId = r.categoryId,
          categoryName = r.categoryId.flatMap(categoryById.get),
          expenseId = r.expenseId,
          notes = r.notes,
          createdAt = r.createdAt
        )
      }
      GetRefunds.Response(data = data)
    }
}
