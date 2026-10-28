package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.CreateImportRule
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.ImportRuleRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateImportRuleAction @Inject() (ruleRepository: ImportRuleRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID, request: CreateImportRule.Request): Future[CreateImportRule.Response] =
    if (ImportRuleMatcher.normalize(request.pattern).length < ImportRuleMatcher.MinPatternLength)
      Future.failed(
        new RuntimeException(s"Rule pattern must be at least ${ImportRuleMatcher.MinPatternLength} characters")
      )
    else
      ruleRepository.create(userId, request.action, request.pattern).map(CreateImportRule.Response(_))
}
