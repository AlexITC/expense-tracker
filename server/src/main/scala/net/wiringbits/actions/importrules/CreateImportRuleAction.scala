package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.{CreateImportRule, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.{CategoriesRepository, ImportRuleRepository, MerchantsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateImportRuleAction @Inject() (
    ruleRepository: ImportRuleRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {

  def apply(userId: UUID, request: CreateImportRule.Request): Future[CreateImportRule.Response] =
    if (ImportRuleMatcher.normalize(request.pattern).length < ImportRuleMatcher.MinPatternLength)
      Future.failed(
        new RuntimeException(s"Rule pattern must be at least ${ImportRuleMatcher.MinPatternLength} characters")
      )
    else if (request.pattern.length > ImportRuleMatcher.MaxPatternLength)
      Future.failed(
        new RuntimeException(s"Rule pattern must be at most ${ImportRuleMatcher.MaxPatternLength} characters")
      )
    else
      request.action match {
        case ImportRuleAction.Ignore =>
          if (request.merchantId.isDefined)
            Future.failed(new RuntimeException("Ignore rules must not have a merchant"))
          else
            for {
              duplicate <- ruleRepository.existsByUserPatternIgnore(userId, request.pattern)
              _ <-
                if (duplicate)
                  Future.failed(new RuntimeException("An ignore rule with that pattern already exists"))
                else Future.unit
              id <- ruleRepository.create(userId, ImportRuleAction.Ignore, request.pattern, None)
            } yield CreateImportRule.Response(id)

        case ImportRuleAction.SetMerchant =>
          val merchantId = request.merchantId.getOrElse(
            throw new RuntimeException("set_merchant rules require a merchant")
          )
          for {
            merchantOpt <- merchantsRepository.find(merchantId, userId)
            _ = if (merchantOpt.isEmpty) throw new RuntimeException("Merchant not found")
            duplicate <- ruleRepository.existsByUserPatternMerchant(userId, request.pattern, merchantId)
            _ <-
              if (duplicate)
                Future.failed(new RuntimeException("A rule with that pattern and merchant already exists"))
              else Future.unit
            id <- ruleRepository.create(userId, ImportRuleAction.SetMerchant, request.pattern, Some(merchantId))
          } yield CreateImportRule.Response(id)

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
              duplicate <- ruleRepository.existsByUserPatternCategory(userId, request.pattern, categoryId)
              _ <-
                if (duplicate)
                  Future.failed(new RuntimeException("A rule with that pattern and category already exists"))
                else Future.unit
              id <- ruleRepository.create(
                userId,
                ImportRuleAction.SetCategory,
                request.pattern,
                None,
                categoryId = Some(categoryId)
              )
            } yield CreateImportRule.Response(id)
          }
      }
}
