package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.{ImportRuleAction, UpdateImportRule}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.{ImportRuleRepository, MerchantsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateImportRuleAction @Inject() (
    ruleRepository: ImportRuleRepository,
    merchantsRepository: MerchantsRepository
)(implicit ec: ExecutionContext) {

  def apply(ruleId: UUID, userId: UUID, request: UpdateImportRule.Request): Future[UpdateImportRule.Response] =
    if (ImportRuleMatcher.normalize(request.pattern).length < 3)
      Future.failed(new RuntimeException("Rule pattern must be at least 3 characters"))
    else
      for {
        ruleOpt <- ruleRepository.find(ruleId, userId)
        _ = if (ruleOpt.isEmpty) throw new RuntimeException("Import rule not found")
        _ <- request.action match {
          case ImportRuleAction.Ignore =>
            if (request.merchantId.isDefined)
              Future.failed(new RuntimeException("Ignore rules must not have a merchant"))
            else
              for {
                duplicate <- ruleRepository.existsByUserPatternIgnore(userId, request.pattern, excludeId = Some(ruleId))
                _ <-
                  if (duplicate)
                    Future.failed(new RuntimeException("An ignore rule with that pattern already exists"))
                  else Future.unit
              } yield ()

          case ImportRuleAction.SetMerchant =>
            val merchantId = request.merchantId.getOrElse(
              throw new RuntimeException("set_merchant rules require a merchant")
            )
            for {
              merchantOpt <- merchantsRepository.find(merchantId, userId)
              _ = if (merchantOpt.isEmpty) throw new RuntimeException("Merchant not found")
              duplicate <- ruleRepository.existsByUserPatternMerchant(
                userId,
                request.pattern,
                merchantId,
                excludeId = Some(ruleId)
              )
              _ <-
                if (duplicate)
                  Future.failed(new RuntimeException("A rule with that pattern and merchant already exists"))
                else Future.unit
            } yield ()
        }
        _ <- ruleRepository.update(ruleId, userId, request.action, request.pattern, request.merchantId)
      } yield UpdateImportRule.Response()
}
