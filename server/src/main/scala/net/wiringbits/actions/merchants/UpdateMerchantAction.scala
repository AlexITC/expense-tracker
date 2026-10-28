package net.wiringbits.actions.merchants

import net.wiringbits.api.models.merchants.UpdateMerchant
import net.wiringbits.repositories.MerchantsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateMerchantAction @Inject() (merchantsRepository: MerchantsRepository)(implicit ec: ExecutionContext) {

  def apply(merchantId: UUID, userId: UUID, request: UpdateMerchant.Request): Future[UpdateMerchant.Response] = {
    for {
      _ <- validate(request)
      _ <- merchantsRepository
        .update(merchantId, userId, request.name, request.notes, request.categoryId, request.taxId)
    } yield UpdateMerchant.Response()
  }

  private def validate(request: UpdateMerchant.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 100) Future.failed(new RuntimeException("Name must be 100 characters or less"))
    else if (request.notes.exists(_.length > 255))
      Future.failed(new RuntimeException("Notes must be 255 characters or less"))
    else if (request.taxId.exists(_.isBlank))
      Future.failed(new RuntimeException("Tax ID cannot be blank"))
    else if (request.taxId.exists(_.length > 50))
      Future.failed(new RuntimeException("Tax ID must be 50 characters or less"))
    else Future.unit
  }
}
