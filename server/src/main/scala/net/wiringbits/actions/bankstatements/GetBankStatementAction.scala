package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.documents.{DocumentStatus => ApiStatus}
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.repositories.BankStatementRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetBankStatementAction @Inject() (bankStatementRepository: BankStatementRepository)(implicit
    ec: ExecutionContext
) {

  def apply(id: UUID, userId: UUID): Future[GetBankStatement.Response] =
    for {
      viewOpt <- bankStatementRepository.find(id, userId)
      view = viewOpt.getOrElse(throw new RuntimeException("Bank statement not found"))
      rows <- bankStatementRepository.findRows(id)
    } yield GetBankStatement.Response(
      id = view.id,
      documentId = view.documentId,
      documentFilename = view.documentFilename,
      documentStatus = ApiStatus.fromEntryName(view.documentStatus.entryName),
      bankName = view.bankName,
      productType = view.productType,
      currency = view.currency,
      accountLast4 = view.accountLast4,
      periodStart = view.periodStart,
      periodEnd = view.periodEnd,
      statementRows = rows.map { r =>
        GetBankStatement.StatementRowData(
          id = r.id,
          index = r.index,
          date = r.date,
          amount = r.amount,
          description = r.description,
          manuallyEdited = r.manuallyEdited,
          confirmedAt = r.confirmedAt,
          expenseId = r.expenseId,
          refundId = r.refundId,
          ignoredAt = r.ignoredAt,
          txnType = r.txnType
        )
      },
      createdAt = view.createdAt,
      extractionInfo = view.extractionInfo,
      aiSuggestions = view.aiSuggestions
    )
}
