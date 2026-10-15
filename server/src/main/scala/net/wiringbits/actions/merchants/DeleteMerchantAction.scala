package net.wiringbits.actions.merchants

import net.wiringbits.api.models.merchants.DeleteMerchant
import net.wiringbits.repositories.MerchantsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteMerchantAction @Inject() (merchantsRepository: MerchantsRepository)(implicit ec: ExecutionContext) {

  def apply(merchantId: UUID, userId: UUID): Future[DeleteMerchant.Response] = {
    for {
      existing <- merchantsRepository.find(merchantId, userId)
      _ = if (existing.isEmpty) throw new RuntimeException("Merchant not found")
      _ <- merchantsRepository.delete(merchantId, userId)
    } yield DeleteMerchant.Response()
  }
}
