package net.wiringbits.actions.bankstatements

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.repositories.models.{BankStatement, BankStatementRow, Document}
import net.wiringbits.repositories.{BankStatementDraftRepository, BankStatementRepository}
import net.wiringbits.services.AiStatementExtractor
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Confirm the current draft into a real statement + rows. Blocks unless the (possibly edited) draft reconciles and the
  * required metadata is present. Writes everything in one transaction and deletes the draft.
  */
class ConfirmBankStatementDraftAction @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(draftId: UUID, userId: UUID): Future[BankStatementDraft.Confirm.Response] =
    for {
      existing <- bankStatementDraftRepository.findByIdForUser(draftId, userId)
      draft = existing.getOrElse(throw new RuntimeException("Draft not found"))
      payloadStr = draft.payload.getOrElse(throw new RuntimeException("The draft is not ready to confirm yet"))
      payload = Json.parse(payloadStr).as[BankStatementDraft.Payload]
      _ = requireReconciled(payload)
      metadata = requireMetadata(payload.metadata)
      now = clock.instant()
      documentId = UuidCreator.getTimeOrderedEpoch()
      stmtId = UuidCreator.getTimeOrderedEpoch()
      document = Document.Create(
        id = documentId,
        userId = userId,
        sha256 = draft.sha256,
        storageKey = draft.storageKey,
        kind = DocumentKind.BankStatementPdf,
        filename = draft.filename,
        status = DocumentStatus.Parsed,
        createdAt = now,
        updatedAt = now
      )
      stmt = BankStatement.Create(stmtId, documentId, userId, createdAt = now, updatedAt = now)
      rows = payload.transactions.zipWithIndex.map { case (t, i) =>
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
      _ <- bankStatementRepository.createConfirmedStatement(document, stmt, metadata, rows)
      _ <- bankStatementDraftRepository.delete(draftId, userId)
    } yield {
      logger.info(s"Confirmed statement draft ${draft.id} into statement $stmtId (${payload.transactions.size} rows)")
      BankStatementDraft.Confirm.Response(stmtId)
    }

  private def requireReconciled(payload: BankStatementDraft.Payload): Unit = {
    val (reconciled, discrepancy) = AiStatementExtractor.reconcile(
      payload.metadata.openingBalance,
      payload.metadata.closingBalance,
      payload.transactions.map(t => (t.amount, t.runningBalance))
    )
    if (!reconciled)
      throw new RuntimeException(s"The draft does not reconcile and can't be imported: ${discrepancy.getOrElse("")}")
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
        BankStatement.Metadata(bn, pt, cur, l4, ps, pe)
      case _ =>
        throw new RuntimeException(s"Missing required metadata: ${missing.mkString(", ")}")
    }
  }
}
