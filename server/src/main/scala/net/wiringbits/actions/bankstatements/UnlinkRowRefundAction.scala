package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.UnlinkRowRefund
import net.wiringbits.repositories.BankStatementRepository

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UnlinkRowRefundAction @Inject() (
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(stmtId: UUID, rowId: UUID, userId: UUID): Future[UnlinkRowRefund.Response] =
    for {
      _ <- bankStatementRepository.find(stmtId, userId).map {
        case None => throw new RuntimeException("Bank statement not found")
        case _ => ()
      }
      _ <- bankStatementRepository.clearRowRefundId(stmtId, rowId, userId, clock.instant())
    } yield UnlinkRowRefund.Response()
}
