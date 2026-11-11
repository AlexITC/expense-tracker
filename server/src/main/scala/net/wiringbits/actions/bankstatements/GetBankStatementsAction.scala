package net.wiringbits.actions.bankstatements

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.bankstatements.GetBankStatements
import net.wiringbits.api.models.documents.DocumentStatus as ApiDocumentStatus
import net.wiringbits.repositories.BankStatementRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetBankStatementsAction @Inject() (bankStatementRepository: BankStatementRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID): Future[GetBankStatements.Response] =
    for {
      stmts <- bankStatementRepository.all(userId)
      overlaps <- bankStatementRepository.overlappingStatementIds(userId)
    } yield {
      val items = stmts.map { s =>
        s.into[GetBankStatements.Response.BankStatement]
          .withFieldConst(_.documentStatus, s.documentStatus.transformInto[ApiDocumentStatus])
          .withFieldConst(_.overlappingStatementIds, overlaps.getOrElse(s.id, Nil))
          .transform
      }
      GetBankStatements.Response(items)
    }
}
