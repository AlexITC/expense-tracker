package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.{ImportRuleAction, UpdateImportRule}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.{CategoriesRepository, ImportRuleRepository, MerchantsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateImportRuleAction @Inject() (
    ruleRepository: ImportRuleRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {

  def apply(ruleId: UUID, userId: UUID, request: UpdateImportRule.Request): Future[UpdateImportRule.Response] =
    if (ImportRuleMatcher.normalize(request.pattern).length < ImportRuleMatcher.MinPatternLength)
      Future.failed(
        new RuntimeException(s"Rule pattern must be at least ${ImportRuleMatcher.MinPatternLength} characters")
      )
    else
      for {
        ruleOpt <- ruleRepository.find(ruleId, userId)
        _ = if (ruleOpt.isEmpty) throw new RuntimeException("Import rule not found")
        _ <- request.action match {
          case ImportRuleAction.Ignore =>
            for {
              duplicate <- ruleRepository.existsByUserPatternIgnore(userId, request.pattern, excludeId = Some(ruleId))
              _ <-
                if (duplicate)
                  Future.failed(new RuntimeException("An ignore rule with that pattern already exists"))
                else Future.unit
            } yield ()

          case ImportRuleAction.SetMerchant(merchantId) =>
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

          case ImportRuleAction.SetCategory(categoryId) =>
            for {
              categoryOpt <- categoriesRepository.find(categoryId, userId)
              _ = if (categoryOpt.isEmpty) throw new RuntimeException("Category not found")
              duplicate <- ruleRepository.existsByUserPatternCategory(
                userId,
                request.pattern,
                categoryId,
                excludeId = Some(ruleId)
              )
              _ <-
                if (duplicate)
                  Future.failed(new RuntimeException("A rule with that pattern and category already exists"))
                else Future.unit
            } yield ()
        }
        _ <- ruleRepository.update(ruleId, userId, request.action, request.pattern)
      } yield UpdateImportRule.Response()
}
