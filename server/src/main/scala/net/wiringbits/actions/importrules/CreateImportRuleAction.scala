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
    else
      request.action match {
        case ImportRuleAction.Ignore =>
          for {
            duplicate <- ruleRepository.existsByUserPatternIgnore(userId, request.pattern)
            _ <-
              if (duplicate)
                Future.failed(new RuntimeException("An ignore rule with that pattern already exists"))
              else Future.unit
            id <- ruleRepository.create(userId, ImportRuleAction.Ignore, request.pattern)
          } yield CreateImportRule.Response(id)

        case action @ ImportRuleAction.SetMerchant(merchantId) =>
          for {
            merchantOpt <- merchantsRepository.find(merchantId, userId)
            _ = if (merchantOpt.isEmpty) throw new RuntimeException("Merchant not found")
            duplicate <- ruleRepository.existsByUserPatternMerchant(userId, request.pattern, merchantId)
            _ <-
              if (duplicate)
                Future.failed(new RuntimeException("A rule with that pattern and merchant already exists"))
              else Future.unit
            id <- ruleRepository.create(userId, action, request.pattern)
          } yield CreateImportRule.Response(id)

        case action @ ImportRuleAction.SetCategory(categoryId) =>
          for {
            categoryOpt <- categoriesRepository.find(categoryId, userId)
            _ = if (categoryOpt.isEmpty) throw new RuntimeException("Category not found")
            duplicate <- ruleRepository.existsByUserPatternCategory(userId, request.pattern, categoryId)
            _ <-
              if (duplicate)
                Future.failed(new RuntimeException("A rule with that pattern and category already exists"))
              else Future.unit
            id <- ruleRepository.create(userId, action, request.pattern)
          } yield CreateImportRule.Response(id)
      }
}
