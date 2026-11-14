package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.CreateImportRule
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.ImportRuleRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateImportRuleAction @Inject() (ruleRepository: ImportRuleRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID, request: CreateImportRule.Request): Future[CreateImportRule.Response] =
    ImportRuleMatcher.validate(request.pattern, request.amount, request.action) match {
      case Left(error) => Future.failed(new RuntimeException(error))
      case Right(()) =>
        ruleRepository
          .create(userId, request.action, request.pattern, request.amount)
          .map(CreateImportRule.Response(_))
    }
}
