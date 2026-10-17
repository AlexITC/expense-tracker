package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import net.wiringbits.services.AiStatementExtractor.{ExtractionResult, Meta}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ExtractBankStatementDraftActionSpec extends AnyWordSpec with Matchers {

  private def result(source: Option[ExtractionSource]): ExtractionResult =
    ExtractionResult(
      reconciled = true,
      discrepancy = None,
      model = "test",
      transactions = Nil,
      metadata = Meta(None, None, None, None, None, None, None, None),
      source = source
    )

  "toPayload" should {
    "carry the stage the extraction ladder tagged on its result" in {
      ExtractBankStatementDraftAction.toPayload(result(Some(ExtractionSource.PdfText))).stage must be(
        Some(ExtractionSource.PdfText)
      )
    }

    "leave the stage empty when the result is untagged" in {
      ExtractBankStatementDraftAction.toPayload(result(None)).stage must be(None)
    }
  }
}
