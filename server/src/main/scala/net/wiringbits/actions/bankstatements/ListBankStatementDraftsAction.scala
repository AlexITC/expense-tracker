package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.services.BankStatementDraftSources

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ListBankStatementDraftsAction @Inject() (bankStatementDraftRepository: BankStatementDraftRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID): Future[BankStatementDraft.Listing.Response] =
    bankStatementDraftRepository.listByUser(userId).map { drafts =>
      BankStatementDraft.Listing.Response(
        drafts.map(d =>
          BankStatementDraft.Summary(
            d.id,
            filename = d.filename,
            source = BankStatementDraftSources.fromDocumentKind(d.documentKind),
            status = d.status,
            d.error
          )
        )
      )
    }
}
