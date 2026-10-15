package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.UnignoreRow
import net.wiringbits.repositories.BankStatementRepository

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UnignoreRowAction @Inject() (
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(stmtId: UUID, rowId: UUID, userId: UUID): Future[UnignoreRow.Response] =
    for {
      _ <- bankStatementRepository.unignoreRow(stmtId, rowId, userId, clock.instant())
    } yield UnignoreRow.Response()
}
