package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.SetAccountAlias
import net.wiringbits.repositories.BankStatementRepository
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SetAccountAliasAction @Inject() (
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(bankStatementId: UUID, userId: UUID, request: SetAccountAlias.Request): Future[SetAccountAlias.Response] =
    if (request.alias.length > SetAccountAlias.MaxAliasLength)
      Future.failed(new RuntimeException(s"Nickname must be ${SetAccountAlias.MaxAliasLength} characters or less"))
    else {
      logger.info(s"Set account alias: stmtId=$bankStatementId userId=$userId")
      bankStatementRepository
        .setAccountAlias(
          bankStatementId = bankStatementId,
          userId = userId,
          alias = request.alias,
          now = clock.instant()
        )
        .map(_ => SetAccountAlias.Response(request.alias))
    }
}
