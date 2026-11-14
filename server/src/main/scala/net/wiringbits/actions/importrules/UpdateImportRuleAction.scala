package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.UpdateImportRule
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.ImportRuleRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateImportRuleAction @Inject() (ruleRepository: ImportRuleRepository)(implicit ec: ExecutionContext) {

  def apply(ruleId: UUID, userId: UUID, request: UpdateImportRule.Request): Future[UpdateImportRule.Response] =
    ImportRuleMatcher.validate(request.pattern, request.amount, request.action) match {
      case Left(error) => Future.failed(new RuntimeException(error))
      case Right(()) =>
        ruleRepository
          .update(id = ruleId, userId = userId, request.action, request.pattern, request.amount)
          .map(_ => UpdateImportRule.Response())
    }
}
