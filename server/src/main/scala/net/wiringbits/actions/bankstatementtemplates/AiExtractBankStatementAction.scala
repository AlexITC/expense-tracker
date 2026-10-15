package net.wiringbits.actions.bankstatementtemplates

import net.wiringbits.api.models.bankstatementtemplates.AiExtractBankStatement
import net.wiringbits.apis.BedrockClient
import net.wiringbits.config.BedrockConfig
import net.wiringbits.repositories.{BankStatementRepository, DocumentsRepository}
import net.wiringbits.services.{AiStatementExtractor, BlobStorage, PdfTextExtractor}
import org.slf4j.LoggerFactory

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Direct AI extraction of a bank statement: load the stored PDF, read its native text (PDFBox), ask the model for the
  * transactions + metadata, and verify against the statement's own arithmetic. Preview only — nothing is persisted.
  */
class AiExtractBankStatementAction @Inject() (
    bankStatementRepository: BankStatementRepository,
    documentsRepository: DocumentsRepository,
    blobStorage: BlobStorage,
    bedrockClient: BedrockClient,
    bedrockConfig: BedrockConfig
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, request: AiExtractBankStatement.Request): Future[AiExtractBankStatement.Response] =
    for {
      stmtOpt <- bankStatementRepository.find(request.bankStatementId, userId)
      stmt = stmtOpt.getOrElse(throw new RuntimeException("Bank statement not found"))
      docOpt <- documentsRepository.findById(stmt.documentId)
      doc = docOpt.getOrElse(throw new RuntimeException("Document not found"))
      bytesOpt <- blobStorage.get(doc.storageKey)
      bytes = bytesOpt.getOrElse(throw new RuntimeException("Statement file not found in storage"))
      text = PdfTextExtractor
        .extract(bytes)
        .fold(err => throw new RuntimeException(err), identity)
      raw <- bedrockClient.extractBankStatement(text)
    } yield {
      val result = AiStatementExtractor
        .parseAndVerify(raw, bedrockConfig.statementModelId)
        .fold(err => throw new RuntimeException(err), identity)
      logger.info(
        s"AI extract for ${request.bankStatementId}: model=${result.model} transactions=${result.transactions.size} " +
          s"reconciled=${result.reconciled}"
      )
      AiExtractBankStatement.Response(
        reconciled = result.reconciled,
        discrepancy = result.discrepancy,
        model = result.model,
        transactions = result.transactions.map(t =>
          AiExtractBankStatement.Transaction(t.date, t.amount, t.description, t.runningBalance, t.txnType)
        ),
        metadata = AiExtractBankStatement.Metadata(
          bankName = result.metadata.bankName,
          productType = result.metadata.productType,
          currency = result.metadata.currency,
          accountLast4 = result.metadata.accountLast4,
          periodStart = result.metadata.periodStart,
          periodEnd = result.metadata.periodEnd,
          openingBalance = result.metadata.openingBalance,
          closingBalance = result.metadata.closingBalance
        )
      )
    }
}
