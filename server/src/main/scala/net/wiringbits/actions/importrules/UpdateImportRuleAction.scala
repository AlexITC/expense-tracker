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
    else if (request.pattern.length > ImportRuleMatcher.MaxPatternLength)
      Future.failed(
        new RuntimeException(s"Rule pattern must be at most ${ImportRuleMatcher.MaxPatternLength} characters")
      )
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

          case ImportRuleAction.SetCategory =>
            if (request.merchantId.isDefined)
              Future.failed(new RuntimeException("set_category rules must not have a merchant"))
            else {
              val categoryId = request.categoryId.getOrElse(
                throw new RuntimeException("set_category rules require a category")
              )
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
        }
        // Only set_category rules keep a category, so switching a rule's action clears a stale one.
        categoryId = if (request.action == ImportRuleAction.SetCategory) request.categoryId else None
        _ <- ruleRepository.update(ruleId, userId, request.action, request.pattern, request.merchantId, categoryId)
      } yield UpdateImportRule.Response()
}
