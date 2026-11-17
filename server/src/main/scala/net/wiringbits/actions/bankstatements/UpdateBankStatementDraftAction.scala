package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.services.{BankStatementDraftReconciliation, BankStatementDraftSources}
import play.api.libs.json.Json

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Save edits to the current draft. Reconciliation is recomputed from the edited payload so the badge (and the confirm
  * gate) reflect the user's changes.
  */
class UpdateBankStatementDraftAction @Inject() (bankStatementDraftRepository: BankStatementDraftRepository)(implicit
    ec: ExecutionContext,
    clock: Clock
) {

  def apply(draftId: UUID, userId: UUID, request: BankStatementDraft.Update.Request): Future[BankStatementDraft.Data] =
    for {
      existing <- bankStatementDraftRepository.findByIdForUser(id = draftId, userId = userId)
      draft = existing.getOrElse(throw new RuntimeException("Draft not found"))
      _ = if (draft.status != "ready") throw new RuntimeException("The draft is still being extracted")
      // Only growth past the cap is rejected, so an extracted draft already over it can still be edited and trimmed.
      existingRows = draft.payload.map(Json.parse(_).as[BankStatementDraft.Payload].transactions.size).getOrElse(0)
      newRows = request.payload.transactions.size
      _ = if (newRows > BankStatementDraftLimits.MaxTransactions && newRows > existingRows)
        throw new RuntimeException(s"A draft can have at most ${BankStatementDraftLimits.MaxTransactions} rows")
      recomputed = BankStatementDraftReconciliation.recompute(request.payload)
      _ <- bankStatementDraftRepository.updatePayload(
        id = draftId,
        userId = userId,
        Json.toJson(recomputed).toString,
        clock.instant()
      )
    } yield BankStatementDraft.Data(
      draft.id,
      filename = draft.filename,
      source = BankStatementDraftSources.fromDocumentKind(draft.documentKind),
      status = "ready",
      None,
      Some(recomputed)
    )
}
