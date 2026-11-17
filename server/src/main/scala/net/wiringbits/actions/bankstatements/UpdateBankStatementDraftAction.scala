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
      stored = draft.payload
        .map(Json.parse(_).as[BankStatementDraft.Payload])
        .getOrElse(throw new RuntimeException("The draft has no extracted data"))
      existingTxns = stored.transactions
      newRows = request.payload.transactions.size
      _ = if (newRows > BankStatementDraftLimits.MaxTransactions && newRows > existingTxns.size)
        throw new RuntimeException(s"A draft can have at most ${BankStatementDraftLimits.MaxTransactions} rows")
      // Rows kept exactly as stored are exempt, so extracted text already over the limits doesn't block other edits.
      _ = validateEditedRows(request.payload.transactions, existingTxns.toSet)
      _ = validateEditedMetadata(metadata = request.payload.metadata, stored = stored.metadata)
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
  private def validateEditedMetadata(
      metadata: BankStatementDraft.Metadata,
      stored: BankStatementDraft.Metadata
  ): Unit = {
    def check(label: String, value: Option[String], storedValue: Option[String], max: Int): Unit =
      if (value != storedValue && value.exists(_.length > max))
        throw new RuntimeException(s"$label is longer than $max characters")
    check(
      label = "Bank name",
      value = metadata.bankName,
      storedValue = stored.bankName,
      max = BankStatementDraftLimits.MaxBankNameLength
    )
    check(
      label = "Product type",
      value = metadata.productType,
      storedValue = stored.productType,
      max = BankStatementDraftLimits.MaxProductTypeLength
    )
    check(
      label = "Currency",
      value = metadata.currency,
      storedValue = stored.currency,
      max = BankStatementDraftLimits.MaxCurrencyLength
    )
    check(
      label = "Account last 4",
      value = metadata.accountLast4,
      storedValue = stored.accountLast4,
      max = BankStatementDraftLimits.MaxAccountLast4Length
    )
  }

  private def validateEditedRows(
      transactions: List[BankStatementDraft.Transaction],
      unchanged: Set[BankStatementDraft.Transaction]
  ): Unit =
    transactions.zipWithIndex.filterNot { case (t, _) => unchanged.contains(t) }.foreach { case (t, index) =>
      val row = index + 1
      if (t.description.length > BankStatementDraftLimits.MaxDescriptionLength)
        throw new RuntimeException(
          s"Row $row: description is longer than ${BankStatementDraftLimits.MaxDescriptionLength} characters"
        )
      if (t.txnType.exists(_.length > BankStatementDraftLimits.MaxTxnTypeLength))
        throw new RuntimeException(
          s"Row $row: type is longer than ${BankStatementDraftLimits.MaxTxnTypeLength} characters"
        )
    }
}
