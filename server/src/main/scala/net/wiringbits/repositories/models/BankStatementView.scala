package net.wiringbits.repositories.models

import net.wiringbits.api.models.bankstatements.ExtractionInfo
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
    documentStatus: DocumentStatus,
    totalRows: Int,
    processedRows: Int,
    extractionInfo: Option[ExtractionInfo]
)
