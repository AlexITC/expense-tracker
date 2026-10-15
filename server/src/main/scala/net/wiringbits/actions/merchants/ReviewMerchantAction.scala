package net.wiringbits.actions.merchants

import net.wiringbits.api.models.merchants.ReviewMerchant
import net.wiringbits.repositories.{CategoriesRepository, MerchantsRepository}
import net.wiringbits.validations.ValidateMerchantNameIsAvailable

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ReviewMerchantAction @Inject() (
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {

  def apply(merchantId: UUID, userId: UUID, request: ReviewMerchant.Request): Future[ReviewMerchant.Response] =
    for {
      merchantOpt <- merchantsRepository.find(merchantId, userId)
      merchant = merchantOpt.getOrElse(throw new RuntimeException("Merchant not found"))
      _ = if (merchant.reviewed) throw new RuntimeException("Merchant is already reviewed")
      _ <- request.name match {
        case Some(name) if name.isBlank => Future.failed(new RuntimeException("Name cannot be blank"))
        case Some(name) if name.length > 100 =>
          Future.failed(new RuntimeException("Name must be 100 characters or less"))
        case Some(name) if !name.equalsIgnoreCase(merchant.name) =>
          ValidateMerchantNameIsAvailable(merchantsRepository, userId, name)
        case _ => Future.unit
      }
      _ <- request.categoryId match {
        case None => Future.unit
        case Some(catId) =>
          categoriesRepository.find(catId, userId).flatMap {
            case None => Future.failed(new RuntimeException("Category not found"))
            case Some(_) => Future.unit
          }
      }
      _ <- merchantsRepository.update(
        merchantId,
        userId,
        name = request.name.getOrElse(merchant.name),
        notes = merchant.notes,
        categoryId = request.categoryId.orElse(merchant.categoryId),
        taxId = merchant.taxId
      )
    } yield ReviewMerchant.Response()
}
