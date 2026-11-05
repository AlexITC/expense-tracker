package net.wiringbits.utils

import net.wiringbits.api.ApiClient
import org.scalajs.dom

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

/** Opens a receipt's file in a new browser tab. Lives outside components because it needs `asInstanceOf`. */
object ReceiptFileViewer {

  def mimeTypeFor(filename: String): String = filename.toLowerCase match {
    case f if f.endsWith(".pdf") => "application/pdf"
    case f if f.endsWith(".jpg") || f.endsWith(".jpeg") => "image/jpeg"
    case f if f.endsWith(".png") => "image/png"
    case _ => "application/octet-stream"
  }

  /** The tab is opened synchronously (before the fetch) so popup blockers allow it. */
  def openInNewTab(client: ApiClient, receiptId: UUID, filename: String, onError: String => Unit)(using
      ExecutionContext
  ): Unit = {
    val win = Option(dom.window.open("", "_blank"))
    val mimeType = mimeTypeFor(filename)
    client.getReceiptFile(receiptId).onComplete {
      case Success((bytes, _, _)) =>
        val typedArray = new js.typedarray.Uint8Array(bytes.length)
        bytes.indices.foreach(i => typedArray(i) = (bytes(i) & 0xff).toShort)
        val blob = new dom.Blob(
          js.Array(typedArray.buffer.asInstanceOf[js.typedarray.ArrayBuffer]),
          new dom.BlobPropertyBag { `type` = mimeType }
        )
        val url = dom.URL.createObjectURL(blob)
        win.foreach(_.location.href = url)
        dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 60000)
      case Failure(ex) =>
        win.foreach(_.close())
        onError(ex.getMessage)
    }
  }
}
