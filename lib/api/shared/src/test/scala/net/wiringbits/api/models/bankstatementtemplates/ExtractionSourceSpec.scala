package net.wiringbits.api.models.bankstatementtemplates

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

class ExtractionSourceSpec extends AnyWordSpec with Matchers {

  private val expected = List(
    ExtractionSource.PdfText -> "pdf_text",
    ExtractionSource.BdaMarkdown -> "bda_markdown",
    ExtractionSource.TextractTables -> "textract_tables",
    ExtractionSource.BdaCustom -> "bda_custom"
  )

  "values" should {
    "contain every stage" in {
      ExtractionSource.values.toSet must be(expected.map(_._1).toSet)
    }
  }

  "format" should {
    "write each value as exactly its entry name and read it back" in {
      expected.foreach { case (source, name) =>
        Json.toJson[ExtractionSource](source) must be(JsString(name))
        JsString(name).validate[ExtractionSource] must be(JsSuccess(source))
      }
    }

    "reject an unknown string" in {
      JsString("PDF text").validate[ExtractionSource] mustBe a[JsError]
    }
  }

  "fromEntryName" should {
    "resolve every value" in {
      expected.foreach { case (source, name) =>
        ExtractionSource.fromEntryName(name) must be(source)
      }
    }

    "throw on an unknown name" in {
      a[NoSuchElementException] must be thrownBy ExtractionSource.fromEntryName("unknown")
    }
  }
}
