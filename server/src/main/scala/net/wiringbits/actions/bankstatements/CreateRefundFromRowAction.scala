package net.wiringbits.actions.bankstatements

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.bankstatements.CreateRefundFromRow
import net.wiringbits.repositories.{BankStatementRepository, RefundsRepository}
import net.wiringbits.repositories.models.Refund
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateRefundFromRowAction @Inject() (
    bankStatementRepository: BankStatementRepository,
    refundsRepository: RefundsRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(
      stmtId: UUID,
      rowId: UUID,
      userId: UUID,
      request: CreateRefundFromRow.Request
  ): Future[CreateRefundFromRow.Response] = {
    logger.info(s"Create refund from row: stmtId=$stmtId rowId=$rowId userId=$userId amount=${request.amount}")
    for {
      _ <- bankStatementRepository.find(stmtId, userId).map {
        case None => throw new RuntimeException("Bank statement not found")
        case _ => ()
      }
      rowOpt <- bankStatementRepository.findRow(rowId, stmtId)
      row = rowOpt.getOrElse(throw new RuntimeException("Bank statement row not found"))
      _ = if (row.amount <= 0) throw new RuntimeException("Only credit rows (amount > 0) can create refunds")
      _ = if (row.refundId.isDefined) throw new RuntimeException("Row is already linked to a refund — unlink first")
      _ = if (request.amount <= 0) throw new RuntimeException("Amount must be greater than 0")
      now = clock.instant()
      refundId = UuidCreator.getTimeOrderedEpoch()
      refund = Refund.Create(
        id = refundId,
        userId = userId,
        amount = request.amount,
        date = request.date,
        merchantId = request.merchantId,
        categoryId = request.categoryId,
        expenseId = request.expenseId,
        notes = request.notes,
        createdAt = now,
        updatedAt = now
      )
      _ <- refundsRepository.create(refund)
      _ <- bankStatementRepository.setRowRefundId(stmtId, rowId, userId, refundId, now)
    } yield CreateRefundFromRow.Response(refundId = refundId)
  }
}
