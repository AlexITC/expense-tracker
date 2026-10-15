package net.wiringbits.actions.importrules

import net.wiringbits.api.models.merchants.DeleteImportRule
import net.wiringbits.repositories.ImportRuleRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteImportRuleAction @Inject() (
    ruleRepository: ImportRuleRepository
)(implicit ec: ExecutionContext) {

  def apply(ruleId: UUID, userId: UUID): Future[DeleteImportRule.Response] =
    ruleRepository.delete(ruleId, userId).map(_ => DeleteImportRule.Response())
}
