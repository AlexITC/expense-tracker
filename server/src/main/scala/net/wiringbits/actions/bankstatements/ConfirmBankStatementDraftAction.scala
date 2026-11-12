package net.wiringbits.actions.bankstatements
import net.wiringbits.validations.BankStatementConfirmChecks

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.bankstatements.{BankStatementDraft, ConfirmWarning, ExtractionInfo}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, BankStatement, BankStatementRow}
import net.wiringbits.repositories.{BankStatementDraftRepository, BankStatementRepository, DocumentsRepository}
import net.wiringbits.services.AiStatementExtractor
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Confirm the current draft into a real statement + rows. Blocks unless the (possibly edited) draft reconciles and the
  * required metadata is present. A draft whose completeness can't be verified (it has transactions but no balances to
  * check them against) is allowed only when `acceptUnverified` is set; this is recomputed from the payload, never read
  * from its stored flag, and recorded on the statement as `acceptedUnverified`. Non-blocking warnings (unusual period,
  * overlap with an existing statement of the same account, a product type or currency that differs from earlier
  * statements of the same card, rows dated outside the stated period, rows dated inside an existing statement of the
  * same account) stop the import unless `acceptWarnings` is set. The draft's metadata and row dates are stored as-is,
  * even when a warning is accepted. Writes everything (plus the AI suggestions job) in one transaction and deletes the
  * draft.
  */
class ConfirmBankStatementDraftAction @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    bankStatementRepository: BankStatementRepository,
    documentsRepository: DocumentsRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(
      draftId: UUID,
      userId: UUID,
      acceptWarnings: Boolean,
      acceptUnverified: Boolean
  ): Future[BankStatementDraft.Confirm.Response] =
    for {
      existing <- bankStatementDraftRepository.findByIdForUser(id = draftId, userId = userId)
      draft = existing.getOrElse(throw new RuntimeException("Draft not found"))
      document <- documentsRepository.find(id = draft.documentId, userId = userId)
      _ = if (document.isEmpty) throw new RuntimeException("The draft's document was not found")
      payloadStr = draft.payload.getOrElse(throw new RuntimeException("The draft is not ready to confirm yet"))
      payload = Json.parse(payloadStr).as[BankStatementDraft.Payload]
      acceptedUnverified = requireReconciled(payload, acceptUnverified)
      metadata = requireMetadata(payload.metadata)
      overlapping <- bankStatementRepository.findOverlapping(userId, metadata)
      sameCard <- bankStatementRepository.findSameCard(userId, metadata)
      rowDates = payload.transactions.map(_.date)
      warnings = BankStatementConfirmChecks.periodWarnings(
        start = metadata.periodStart,
        end = metadata.periodEnd,
        today = LocalDate.now(clock)
      ) ++ BankStatementConfirmChecks.overlapWarning(overlapping).toList ++
        BankStatementConfirmChecks.cardMismatchWarnings(metadata, sameCard) ++
        BankStatementConfirmChecks
          .rowsOutsidePeriodWarning(
            rowDates = rowDates,
            periodStart = metadata.periodStart,
            periodEnd = metadata.periodEnd
          )
          .toList ++
        BankStatementConfirmChecks
          .rowsInOtherStatementWarning(metadata = metadata, rowDates = rowDates, sameCard = sameCard)
          .toList
      response <-
        if (warnings.nonEmpty && !acceptWarnings)
          Future.successful(BankStatementDraft.Confirm.Response(None, warnings))
        else create(draft, payload, metadata, userId, warnings, acceptedUnverified)
    } yield response

  private def create(
      draft: net.wiringbits.repositories.models.BankStatementDraft,
      payload: BankStatementDraft.Payload,
      metadata: BankStatement.Metadata,
      userId: UUID,
      warnings: List[ConfirmWarning],
      acceptedUnverified: Boolean
  ): Future[BankStatementDraft.Confirm.Response] = {
    val now = clock.instant()
    val stmtId = UuidCreator.getTimeOrderedEpoch()
    val stmt = BankStatement.Create(
      id = stmtId,
      documentId = draft.documentId,
      userId = userId,
      createdAt = now,
      updatedAt = now,
      acceptedUnverified = acceptedUnverified
    )
    val rows = payload.transactions.zipWithIndex.map { case (t, i) =>
      BankStatementRow.Create(
        id = UuidCreator.getTimeOrderedEpoch(),
        bankStatementId = stmtId,
        index = i,
        date = t.date,
        amount = t.amount,
        description = t.description,
        manuallyEdited = false,
        createdAt = now,
        updatedAt = now,
        txnType = t.txnType.getOrElse("purchase")
      )
    }
    val extractionInfo = payload.stage.map(s => payload.into[ExtractionInfo].withFieldConst(_.stage, s).transform)
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ResolveStatementSuggestions,
      payload = BackgroundJobPayload.ResolveStatementSuggestions(stmtId),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
    for {
      _ <- bankStatementRepository.createConfirmedStatement(stmt, metadata, rows, extractionInfo, job)
      _ <- bankStatementDraftRepository.delete(id = draft.id, userId = userId)
    } yield {
      logger.info(
        s"Confirmed statement draft ${draft.id} for user $userId into statement $stmtId (document ${draft.documentId}) (${payload.transactions.size} rows), enqueued suggestions job ${job.id}"
      )
      if (acceptedUnverified)
        logger.info(s"Statement $stmtId for user $userId was confirmed with unverifiable completeness accepted")
      if (warnings.nonEmpty)
        logger.info(
          s"Statement $stmtId for user $userId was confirmed with accepted warnings: ${warnings.map(_.message).mkString("; ")}"
        )
      BankStatementDraft.Confirm.Response(Some(stmtId), warnings)
    }
  }

  /** Recompute reconciliation from the payload (the stored flags are not trusted). Returns whether the draft is being
    * accepted unverified; throws when it can't be imported.
    */
  private def requireReconciled(payload: BankStatementDraft.Payload, acceptUnverified: Boolean): Boolean = {
    val r = AiStatementExtractor.reconcile(
      openingBalance = payload.metadata.openingBalance,
      closingBalance = payload.metadata.closingBalance,
      payload.transactions.map(t => (t.amount, t.runningBalance))
    )
    if (r.reconciled) false
    else if (AiStatementExtractor.isTerminalUnverifiable(r.unverifiable, payload.transactions.size) && acceptUnverified)
      true
    else
      throw new RuntimeException(s"The draft does not reconcile and can't be imported: ${r.discrepancy.getOrElse("")}")
  }

  /** Validate that every required metadata field is present and return it as the storage model. */
  private def requireMetadata(m: BankStatementDraft.Metadata): BankStatement.Metadata = {
    def text(v: Option[String]): Option[String] = v.map(_.trim).filter(_.nonEmpty)
    val bankName = text(m.bankName)
    val productType = text(m.productType)
    val currency = text(m.currency)
    val accountLast4 = text(m.accountLast4)
    val missing = List(
      Option.when(bankName.isEmpty)("bank name"),
      Option.when(productType.isEmpty)("product type"),
      Option.when(currency.isEmpty)("currency"),
      Option.when(accountLast4.isEmpty)("account number"),
      Option.when(m.periodStart.isEmpty)("period start"),
      Option.when(m.periodEnd.isEmpty)("period end")
    ).flatten
    (bankName, productType, currency, accountLast4, m.periodStart, m.periodEnd) match {
      case (Some(bn), Some(pt), Some(cur), Some(l4), Some(ps), Some(pe)) =>
        BankStatement.Metadata(
          bankName = bn,
          productType = pt,
          currency = cur,
          accountLast4 = l4,
          periodStart = ps,
          periodEnd = pe
        )
      case _ =>
        throw new RuntimeException(s"Missing required metadata: ${missing.mkString(", ")}")
    }
  }
}
