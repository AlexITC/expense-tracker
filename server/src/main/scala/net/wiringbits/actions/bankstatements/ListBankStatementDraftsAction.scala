package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.repositories.BankStatementDraftRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ListBankStatementDraftsAction @Inject() (bankStatementDraftRepository: BankStatementDraftRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID): Future[BankStatementDraft.Listing.Response] =
    bankStatementDraftRepository.listByUser(userId).map { drafts =>
      BankStatementDraft.Listing.Response(
        drafts.map(d => BankStatementDraft.Summary(d.id, d.filename, d.status, d.error))
      )
    }
}
