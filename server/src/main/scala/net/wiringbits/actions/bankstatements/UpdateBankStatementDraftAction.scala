package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.services.AiStatementExtractor
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
      existing <- bankStatementDraftRepository.findByIdForUser(draftId, userId)
      draft = existing.getOrElse(throw new RuntimeException("Draft not found"))
      _ = if (draft.status != "ready") throw new RuntimeException("The draft is still being extracted")
      recomputed = recompute(request.payload)
      _ <- bankStatementDraftRepository.updatePayload(
        draftId,
        userId,
        Json.toJson(recomputed).toString,
        clock.instant()
      )
    } yield BankStatementDraft.Data(draft.id, draft.filename, "ready", None, Some(recomputed))

  private def recompute(payload: BankStatementDraft.Payload): BankStatementDraft.Payload = {
    val (reconciled, discrepancy) = AiStatementExtractor.reconcile(
      payload.metadata.openingBalance,
      payload.metadata.closingBalance,
      payload.transactions.map(t => (t.amount, t.runningBalance))
    )
    payload.copy(reconciled = reconciled, discrepancy = discrepancy)
  }
}
