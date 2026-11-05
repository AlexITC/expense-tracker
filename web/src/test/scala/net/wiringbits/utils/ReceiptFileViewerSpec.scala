package net.wiringbits.utils

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class ReceiptFileViewerSpec extends AnyWordSpec {

  "mimeTypeFor" should {
    "map .pdf to application/pdf" in {
      ReceiptFileViewer.mimeTypeFor("receipt.pdf") mustBe "application/pdf"
    }

    "map .jpg to image/jpeg" in {
      ReceiptFileViewer.mimeTypeFor("receipt.jpg") mustBe "image/jpeg"
    }

    "map .jpeg to image/jpeg" in {
      ReceiptFileViewer.mimeTypeFor("receipt.jpeg") mustBe "image/jpeg"
    }

    "map .png to image/png" in {
      ReceiptFileViewer.mimeTypeFor("receipt.png") mustBe "image/png"
    }

    "ignore extension case" in {
      ReceiptFileViewer.mimeTypeFor("RECEIPT.PDF") mustBe "application/pdf"
      ReceiptFileViewer.mimeTypeFor("Receipt.JPG") mustBe "image/jpeg"
      ReceiptFileViewer.mimeTypeFor("receipt.JpEg") mustBe "image/jpeg"
      ReceiptFileViewer.mimeTypeFor("receipt.PNG") mustBe "image/png"
    }

    "fall back to application/octet-stream for unknown extensions" in {
      ReceiptFileViewer.mimeTypeFor("receipt.heic") mustBe "application/octet-stream"
      ReceiptFileViewer.mimeTypeFor("receipt") mustBe "application/octet-stream"
    }
  }
}
