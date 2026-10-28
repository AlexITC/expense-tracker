package net.wiringbits.actions.merchants

import net.wiringbits.api.models.merchants.MarkMerchantAsDuplicate
import net.wiringbits.repositories.{ImportRuleRepository, MerchantsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class MarkMerchantAsDuplicateAction @Inject() (
    merchantsRepository: MerchantsRepository,
    ruleRepository: ImportRuleRepository
)(implicit ec: ExecutionContext) {

  def apply(
      pendingId: UUID,
      userId: UUID,
      request: MarkMerchantAsDuplicate.Request
  ): Future[MarkMerchantAsDuplicate.Response] =
    for {
      pendingOpt <- merchantsRepository.find(pendingId, userId)
      pending = pendingOpt.getOrElse(throw new RuntimeException("Merchant not found"))
      _ = if (pending.reviewed) throw new RuntimeException("Merchant is already reviewed")
      _ = if (pending.id == request.realMerchantId)
        throw new RuntimeException("Cannot mark a merchant as a duplicate of itself")
      realOpt <- merchantsRepository.find(request.realMerchantId, userId)
      _ = if (realOpt.isEmpty) throw new RuntimeException("Real merchant not found")
      _ <- ruleRepository.createSetMerchantIfAbsent(userId, request.realMerchantId, pending.name)
      _ <- merchantsRepository.rePointAndDelete(pendingId, request.realMerchantId, userId)
    } yield MarkMerchantAsDuplicate.Response()
}
