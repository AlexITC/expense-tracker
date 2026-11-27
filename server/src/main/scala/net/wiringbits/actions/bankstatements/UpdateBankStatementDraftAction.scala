package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.services.{
  BankStatementDraftReconciliation,
  BankStatementDraftSources,
  BankStatementPayloadValidator
}
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
      stored = draft.payload
        .map(Json.parse(_).as[BankStatementDraft.Payload])
        .getOrElse(throw new RuntimeException("The draft has no extracted data"))
      existingTxns = stored.transactions
      newRows = request.payload.transactions.size
      _ = if (newRows > BankStatementDraftLimits.MaxTransactions && newRows > existingTxns.size)
        throw new RuntimeException(s"A draft can have at most ${BankStatementDraftLimits.MaxTransactions} rows")
      // Rows kept exactly as stored are exempt, so extracted text already over the limits doesn't block other edits.
      _ = validateEdits(payload = request.payload, stored = stored)
      // The model is set by whoever produced the draft, never by the client; discrepancy is re-derived by recompute.
      recomputed = BankStatementDraftReconciliation.recompute(request.payload.copy(model = stored.model))
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

  // Values kept exactly as stored are exempt, like rows, so extracted text already over a limit doesn't block edits.
  private def validateEdits(
      payload: BankStatementDraft.Payload,
      stored: BankStatementDraft.Payload
  ): Unit = {
    val m = payload.metadata
    val s = stored.metadata
    val header = BankStatementPayloadValidator.Header(
      bankName = m.bankName.filter(_ => m.bankName != s.bankName),
      productType = m.productType.filter(_ => m.productType != s.productType),
      currency = m.currency.filter(_ => m.currency != s.currency),
      accountLast4 = m.accountLast4.filter(_ => m.accountLast4 != s.accountLast4),
      openingBalance = m.openingBalance.filter(_ => m.openingBalance != s.openingBalance),
      closingBalance = m.closingBalance.filter(_ => m.closingBalance != s.closingBalance)
    )
    val unchanged = stored.transactions.toSet
    val result = payload.transactions.zipWithIndex
      .filterNot { case (t, _) => unchanged.contains(t) }
      .foldLeft(BankStatementPayloadValidator.validateHeader(header)) { case (acc, (t, index)) =>
        acc.flatMap(_ =>
          BankStatementPayloadValidator.validateRow(
            index + 1,
            BankStatementPayloadValidator.Row(
              description = t.description,
              amount = t.amount,
              txnType = t.txnType,
              runningBalance = t.runningBalance
            )
          )
        )
      }
    result match {
      case Left(msg) => throw new RuntimeException(msg)
      case Right(()) => ()
    }
  }
}
