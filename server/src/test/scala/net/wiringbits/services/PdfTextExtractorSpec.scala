package net.wiringbits.services

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class PdfTextExtractorSpec extends AnyWordSpec {

  "PdfTextExtractor.usableText" should {
    "reject empty or near-empty text (a scanned/image-only PDF)" in {
      PdfTextExtractor.usableText("").isLeft mustBe true
      PdfTextExtractor.usableText("   \n  ").isLeft mustBe true
      PdfTextExtractor.usableText("Page 1 of 3").isLeft mustBe true
    }

    "accept text long enough to be a real statement" in {
      val text = "BBVA\n" + ("01.04.26 SOME MERCHANT GIJON 12,34\n" * 5)
      PdfTextExtractor.usableText(text) mustBe Right(text)
    }
  }
}
