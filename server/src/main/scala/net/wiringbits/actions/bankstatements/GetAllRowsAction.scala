package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.GetAllRows
import net.wiringbits.repositories.BankStatementRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetAllRowsAction @Inject() (
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetAllRows.Response] =
    bankStatementRepository.allRows(userId).map { rows =>
      val data = rows.map { r =>
        GetAllRows.RowData(
          id = r.id,
          bankStatementId = r.bankStatementId,
          date = r.date,
          amount = r.amount,
          description = r.description,
          expenseId = r.expenseId,
          ignoredAt = r.ignoredAt
        )
      }
      GetAllRows.Response(data)
    }
}
