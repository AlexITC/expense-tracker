package net.wiringbits.repositories.models

import net.wiringbits.api.models.bankstatements.{AiSuggestions, ExtractionInfo}

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
)
