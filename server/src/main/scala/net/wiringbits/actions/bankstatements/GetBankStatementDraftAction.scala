package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.repositories.BankStatementDraftRepository
import play.api.libs.json.Json

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetBankStatementDraftAction @Inject() (bankStatementDraftRepository: BankStatementDraftRepository)(implicit
    ec: ExecutionContext
) {

  def apply(draftId: UUID, userId: UUID): Future[BankStatementDraft.Data] =
    bankStatementDraftRepository.findByIdForUser(draftId, userId).map {
      case Some(draft) =>
        val payload = draft.payload.map(Json.parse(_).as[BankStatementDraft.Payload])
        BankStatementDraft.Data(draft.id, draft.filename, draft.status, draft.error, payload)
      case None =>
        throw new RuntimeException("Draft not found")
    }
}
