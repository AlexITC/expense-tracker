package net.wiringbits.repositories.models

import net.wiringbits.api.models.bankstatements.{AiSuggestions, ExtractionInfo}
import net.wiringbits.models.documents.DocumentStatus

import java.time.{Instant, LocalDate}
import java.util.UUID

case class BankStatementView(
    id: UUID,
    documentId: UUID,
    userId: UUID,
    bankName: String,
    productType: String,
    currency: String,
    accountLast4: String,
    periodStart: LocalDate,
    periodEnd: LocalDate,
    createdAt: Instant,
    updatedAt: Instant,
    documentFilename: String,
    totalRows: Int,
    processedRows: Int,
    extractionInfo: Option[ExtractionInfo],
    aiSuggestions: Option[AiSuggestions]
) {

  // A bank_statement row only exists once a draft is confirmed (statement-at-confirm), and a confirmed statement is
  // fully parsed, so this is the invariant rather than a guessed default.
  def documentStatus: DocumentStatus = DocumentStatus.Parsed
}
