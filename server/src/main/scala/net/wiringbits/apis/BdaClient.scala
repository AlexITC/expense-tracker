package net.wiringbits.apis

import play.api.libs.json.JsValue

import scala.concurrent.Future

/** Amazon Bedrock Data Automation. Both calls read the PDF from S3 (by storage key) and run to completion. */
trait BdaClient {

  /** Standard output: the document rendered as markdown (balance box + tables preserved). None if BDA produced none. */
  def standardMarkdown(storageKey: String): Future[Option[String]]

  /** Custom output via the bank-statement blueprint: the structured inference_result JSON. None on failure. */
  def customExtraction(storageKey: String): Future[Option[JsValue]]
}
