package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.BankStatementDraftSource
import net.wiringbits.models.documents.DocumentKind

object BankStatementDraftSources {

  /** A draft only ever links a statement document, so any other kind means corrupted data. */
  def fromDocumentKind(kind: DocumentKind): BankStatementDraftSource = kind match {
    case DocumentKind.BankStatementPdf => BankStatementDraftSource.Pdf
    case DocumentKind.BankStatementCsv => BankStatementDraftSource.Csv
    case DocumentKind.Receipt | DocumentKind.Invoice =>
      throw new IllegalStateException(s"A bank statement draft can't link a ${kind.entryName} document")
  }
}
