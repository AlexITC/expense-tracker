package net.wiringbits.actions.merchants

import net.wiringbits.api.models.merchants.GetPendingMerchants
import net.wiringbits.repositories.MerchantsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetPendingMerchantsAction @Inject() (merchantsRepository: MerchantsRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetPendingMerchants.Response] =
    merchantsRepository.findPending(userId).map { merchants =>
      GetPendingMerchants.Response(merchants.map(m => GetPendingMerchants.Merchant(m.id, m.name, m.createdAt)))
    }
}
