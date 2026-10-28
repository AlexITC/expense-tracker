package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.UpdateImportRule
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.ImportRuleRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateImportRuleAction @Inject() (ruleRepository: ImportRuleRepository)(implicit ec: ExecutionContext) {

  def apply(ruleId: UUID, userId: UUID, request: UpdateImportRule.Request): Future[UpdateImportRule.Response] =
    if (ImportRuleMatcher.normalize(request.pattern).length < ImportRuleMatcher.MinPatternLength)
      Future.failed(
        new RuntimeException(s"Rule pattern must be at least ${ImportRuleMatcher.MinPatternLength} characters")
      )
    else
      ruleRepository.update(ruleId, userId, request.action, request.pattern).map(_ => UpdateImportRule.Response())
}
