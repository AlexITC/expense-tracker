package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.IgnoreRow
import net.wiringbits.repositories.BankStatementRepository
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class IgnoreRowAction @Inject() (
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(stmtId: UUID, rowId: UUID, userId: UUID): Future[IgnoreRow.Response] = {
    logger.info(s"Ignore bank statement row: stmtId=$stmtId rowId=$rowId userId=$userId")
    val now = clock.instant()
    for {
      _ <- bankStatementRepository.ignoreRow(stmtId, rowId, userId, now, now)
    } yield IgnoreRow.Response()
  }
}
