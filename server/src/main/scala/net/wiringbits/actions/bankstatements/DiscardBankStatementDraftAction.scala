package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.repositories.BankStatementDraftRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DiscardBankStatementDraftAction @Inject() (bankStatementDraftRepository: BankStatementDraftRepository)(implicit
    ec: ExecutionContext
) {

  def apply(draftId: UUID, userId: UUID): Future[BankStatementDraft.Discard.Response] =
    bankStatementDraftRepository.delete(draftId, userId).map(deleted => BankStatementDraft.Discard.Response(deleted))
}
