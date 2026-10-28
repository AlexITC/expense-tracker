package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.GetBankStatements
import net.wiringbits.repositories.BankStatementRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetBankStatementsAction @Inject() (bankStatementRepository: BankStatementRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID): Future[GetBankStatements.Response] =
    bankStatementRepository.all(userId).map { stmts =>
      val items = stmts.map { s =>
        GetBankStatements.Response.BankStatement(
          id = s.id,
          documentFilename = s.documentFilename,
          documentStatus = s.documentStatus.toApi,
          bankName = s.bankName,
          productType = s.productType,
          currency = s.currency,
          accountLast4 = s.accountLast4,
          periodStart = s.periodStart,
          periodEnd = s.periodEnd,
          createdAt = s.createdAt,
          totalRows = s.totalRows,
          processedRows = s.processedRows
        )
      }
      GetBankStatements.Response(items)
    }
}
