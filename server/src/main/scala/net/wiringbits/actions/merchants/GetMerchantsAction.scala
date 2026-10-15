package net.wiringbits.actions.merchants

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.repositories.MerchantsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetMerchantsAction @Inject() (merchantsRepository: MerchantsRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetMerchants.Response] = {
    for {
      merchants <- merchantsRepository.all(userId)
      items = merchants.map(_.transformInto[GetMerchants.Response.Merchant])
    } yield GetMerchants.Response(items)
  }
}
