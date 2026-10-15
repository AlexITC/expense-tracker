package net.wiringbits.actions.merchants

import net.wiringbits.api.models.merchants.UpdateMerchant
import net.wiringbits.repositories.{CategoriesRepository, MerchantsRepository}
import net.wiringbits.validations.ValidateMerchantNameIsAvailable
import org.postgresql.util.PSQLException

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateMerchantAction @Inject() (
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {

  def apply(merchantId: UUID, userId: UUID, request: UpdateMerchant.Request): Future[UpdateMerchant.Response] = {
    for {
      _ <- validate(merchantId, userId, request)
      _ <- merchantsRepository
        .update(merchantId, userId, request.name, request.notes, request.categoryId, request.taxId)
        .recoverWith {
          case ex: PSQLException if ex.getMessage.contains("merchant_tax_id_user_idx") =>
            Future.failed(new RuntimeException("A merchant with that tax ID already exists"))
        }
    } yield UpdateMerchant.Response()
  }

  private def validate(merchantId: UUID, userId: UUID, request: UpdateMerchant.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 100) Future.failed(new RuntimeException("Name must be 100 characters or less"))
    else if (request.notes.exists(_.length > 255))
      Future.failed(new RuntimeException("Notes must be 255 characters or less"))
    else if (request.taxId.exists(_.isBlank))
      Future.failed(new RuntimeException("Tax ID cannot be blank"))
    else if (request.taxId.exists(_.length > 50))
      Future.failed(new RuntimeException("Tax ID must be 50 characters or less"))
    else
      for {
        existing <- merchantsRepository.find(merchantId, userId)
        _ = if (existing.isEmpty) throw new RuntimeException("Merchant not found")
        _ <- ValidateMerchantNameIsAvailable(merchantsRepository, userId, request.name, excludeId = Some(merchantId))
        _ <- request.categoryId match {
          case None => Future.unit
          case Some(catId) =>
            categoriesRepository.find(catId, userId).flatMap {
              case None => Future.failed(new RuntimeException("Category not found"))
              case Some(_) => Future.unit
            }
        }
      } yield ()
  }
}
