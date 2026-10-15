package net.wiringbits.services

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

import scala.util.{Try, Using}

/** Extracts the native text layer from a PDF (no OCR). Returns Left when the PDF has no usable text (e.g. a scan) or
  * cannot be read, so callers can fall back to OCR/VLM.
  */
object PdfTextExtractor {
  // A real statement has plenty of text; anything under this is an image-only/scanned PDF whose native text layer is
  // empty or near-empty (a stray page number or letterhead), which the model can't extract from.
  private val MinUsableChars = 100

  def extract(bytes: Array[Byte]): Either[String, String] =
    Try {
      Using.resource(Loader.loadPDF(bytes)) { doc =>
        val stripper = new PDFTextStripper()
        stripper.setSortByPosition(true) // keep columns roughly aligned, closest to `pdftotext -layout`
        stripper.getText(doc)
      }
    }.toEither.left
      .map(e => s"PDF text extraction failed: ${e.getMessage}")
      .flatMap(usableText)

  /** Reject an image-only/scanned PDF whose native text layer is empty or near-empty, before anything tries to extract
    * transactions from it.
    */
  def usableText(text: String): Either[String, String] =
    if (text.trim.length < MinUsableChars)
      Left("PDF has no usable text layer (likely a scanned/image-only PDF); it can't be extracted")
    else Right(text)
}
