package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.{GetImportRules, ImportRuleAction}
import net.wiringbits.repositories.ImportRuleRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetImportRulesAction @Inject() (
    ruleRepository: ImportRuleRepository
)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetImportRules.Response] =
    ruleRepository.all(userId).map { rules =>
      GetImportRules.Response(rules.map { r =>
        GetImportRules.Rule(
          id = r.id,
          action = r.action,
          pattern = r.pattern,
          merchantId = r.merchantId,
          merchantName = r.merchantName,
          categoryId = r.categoryId,
          categoryName = r.categoryName,
          autoCreated = r.autoCreated,
          createdAt = r.createdAt,
          updatedAt = r.updatedAt
        )
      })
    }
}
