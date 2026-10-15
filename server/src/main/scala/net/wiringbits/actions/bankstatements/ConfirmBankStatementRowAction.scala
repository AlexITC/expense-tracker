package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.ConfirmBankStatementRow
import net.wiringbits.repositories.BankStatementRepository
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ConfirmBankStatementRowAction @Inject() (
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(
      bankStatementId: UUID,
      rowId: UUID,
      userId: UUID,
      request: ConfirmBankStatementRow.Request
  ): Future[ConfirmBankStatementRow.Response] = {
    logger.info(s"Confirm bank statement row: stmtId=$bankStatementId rowId=$rowId userId=$userId")
    for {
      viewOpt <- bankStatementRepository.find(bankStatementId, userId)
      _ = viewOpt.getOrElse(throw new RuntimeException("Bank statement not found"))
      rowOpt <- bankStatementRepository.findRow(rowId, bankStatementId)
      row = rowOpt.getOrElse(throw new RuntimeException("Bank statement row not found"))
      manuallyEdited =
        row.date != request.date || row.amount != request.amount || row.description != request.description
      now = clock.instant()
      _ <- bankStatementRepository.confirmRow(
        rowId = rowId,
        bankStatementId = bankStatementId,
        date = request.date,
        amount = request.amount,
        description = request.description,
        manuallyEdited = manuallyEdited,
        confirmedAt = now,
        updatedAt = now
      )
    } yield ConfirmBankStatementRow.Response(confirmedAt = now)
  }
}
