package net.wiringbits.actions.bankstatements

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.repositories.BankStatementRepository
import org.slf4j.LoggerFactory

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetBankStatementAction @Inject() (bankStatementRepository: BankStatementRepository)(implicit
    ec: ExecutionContext
) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(id: UUID, userId: UUID): Future[GetBankStatement.Response] =
    for {
      viewOpt <- bankStatementRepository.find(id, userId)
      view = viewOpt.getOrElse(throw new RuntimeException("Bank statement not found"))
      _ = logger.info(s"Get bank statement $id for user $userId")
      rows <- bankStatementRepository.findRows(id)
      linkedExpenses <- bankStatementRepository.findRowLinkedExpenses(id, userId)
      linkedByRowId = linkedExpenses.map(l => l.rowId -> l).toMap
    } yield GetBankStatement.Response(
      id = view.id,
      documentId = view.documentId,
      documentFilename = view.documentFilename,
      documentStatus = view.documentStatus.toApi,
      bankName = view.bankName,
      productType = view.productType,
      currency = view.currency,
      accountLast4 = view.accountLast4,
      periodStart = view.periodStart,
      periodEnd = view.periodEnd,
      statementRows = rows.map { r =>
        r.into[GetBankStatement.StatementRowData]
          .withFieldComputed(_.expenseMerchantId, r => linkedByRowId.get(r.id).flatMap(_.merchantId))
          .withFieldComputed(_.expenseCategoryId, r => linkedByRowId.get(r.id).flatMap(_.categoryId))
          .transform
      },
      createdAt = view.createdAt,
      extractionInfo = view.extractionInfo,
      aiSuggestions = view.aiSuggestions
    )
}
