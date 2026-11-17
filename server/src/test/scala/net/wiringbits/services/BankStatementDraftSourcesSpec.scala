package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.BankStatementDraftSource
import net.wiringbits.models.documents.DocumentKind
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BankStatementDraftSourcesSpec extends AnyWordSpec with Matchers {

  "fromDocumentKind" should {
    "map a PDF statement to Pdf" in {
      BankStatementDraftSources.fromDocumentKind(DocumentKind.BankStatementPdf) must be(BankStatementDraftSource.Pdf)
    }

    "map a CSV statement to Csv" in {
      BankStatementDraftSources.fromDocumentKind(DocumentKind.BankStatementCsv) must be(BankStatementDraftSource.Csv)
    }

    "reject kinds a draft can never link" in {
      List(DocumentKind.Receipt, DocumentKind.Invoice).foreach { kind =>
        an[IllegalStateException] must be thrownBy BankStatementDraftSources.fromDocumentKind(kind)
      }
    }
  }
}
