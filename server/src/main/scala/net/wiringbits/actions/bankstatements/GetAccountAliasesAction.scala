package net.wiringbits.actions.bankstatements

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.bankstatements.GetAccountAliases
import net.wiringbits.repositories.BankStatementRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetAccountAliasesAction @Inject() (bankStatementRepository: BankStatementRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID): Future[GetAccountAliases.Response] =
    bankStatementRepository.listAccountAliases(userId).map { aliases =>
      GetAccountAliases.Response(aliases.map(_.transformInto[GetAccountAliases.Account]))
    }
}
