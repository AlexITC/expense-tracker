package net.wiringbits.actions.savings

import net.wiringbits.api.models.savings.GetSavingsEstimate
import net.wiringbits.repositories.SavingsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetSavingsEstimateAction @Inject() (savingsRepository: SavingsRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID, merchantId: UUID): Future[GetSavingsEstimate.Response] = {
    for {
      typicalAmount <- savingsRepository.medianAmountByMerchant(userId, merchantId)
    } yield GetSavingsEstimate.Response(typicalAmount)
  }
}
