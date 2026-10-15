package controllers

import net.wiringbits.actions.receipts.*
import net.wiringbits.api.endpoints.ReceiptsEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.receipts.{
  ConfirmReceipt,
  DeleteReceipt,
  GetReceipt,
  GetReceipts,
  IgnoreReceipt,
  LinkReceiptToExpense,
  LinkReceiptToRefund,
  UnignoreReceipt,
  UnlinkReceipt,
  UnlinkReceiptFromRefund,
  UploadReceipt
}
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ReceiptsController @Inject() (
    uploadReceiptAction: UploadReceiptAction,
    getReceiptsAction: GetReceiptsAction,
    getReceiptAction: GetReceiptAction,
    getReceiptFileAction: GetReceiptFileAction,
    confirmReceiptAction: ConfirmReceiptAction,
    deleteReceiptAction: DeleteReceiptAction,
    linkReceiptToExpenseAction: LinkReceiptToExpenseAction,
    unlinkReceiptAction: UnlinkReceiptAction,
    linkReceiptToRefundAction: LinkReceiptToRefundAction,
    unlinkReceiptFromRefundAction: UnlinkReceiptFromRefundAction,
    ignoreReceiptAction: IgnoreReceiptAction,
    unignoreReceiptAction: UnignoreReceiptAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def upload(
      bytes: Array[Byte],
      kind: String,
      filename: String,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UploadReceipt.Response]] = handleRequest {
    logger.info(s"Upload receipt: kind=$kind, filename=$filename, size=${bytes.length}")
    for {
      userId <- userIdF
      response <- uploadReceiptAction(userId, bytes, kind, filename)
    } yield Right(response)
  }

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetReceipts.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getReceiptsAction(userId)
    } yield Right(response)
  }

  private def getOne(
      receiptId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, GetReceipt.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getReceiptAction(receiptId, userId)
    } yield Right(response)
  }

  private def confirm(
      receiptId: UUID,
      request: ConfirmReceipt.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, ConfirmReceipt.Response]] = handleRequest {
    logger.info(s"Confirm receipt: $receiptId")
    for {
      userId <- userIdF
      response <- confirmReceiptAction(receiptId, userId, request)
    } yield Right(response)
  }

  private def delete(
      receiptId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteReceipt.Response]] = handleRequest {
    logger.info(s"Delete receipt: $receiptId")
    for {
      userId <- userIdF
      response <- deleteReceiptAction(receiptId, userId)
    } yield Right(response)
  }

  private def link(
      receiptId: UUID,
      request: LinkReceiptToExpense.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, LinkReceiptToExpense.Response]] = handleRequest {
    logger.info(s"Link receipt to expense: receipt=$receiptId expense=${request.expenseId}")
    for {
      userId <- userIdF
      response <- linkReceiptToExpenseAction(receiptId, userId, request)
    } yield Right(response)
  }

  private def unlink(
      receiptId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UnlinkReceipt.Response]] = handleRequest {
    logger.info(s"Unlink receipt: $receiptId")
    for {
      userId <- userIdF
      response <- unlinkReceiptAction(receiptId, userId)
    } yield Right(response)
  }

  private def linkToRefund(
      receiptId: UUID,
      request: LinkReceiptToRefund.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, LinkReceiptToRefund.Response]] = handleRequest {
    logger.info(s"Link receipt to refund: receipt=$receiptId refund=${request.refundId}")
    for {
      userId <- userIdF
      response <- linkReceiptToRefundAction(receiptId, userId, request)
    } yield Right(response)
  }

  private def unlinkFromRefund(
      receiptId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UnlinkReceiptFromRefund.Response]] = handleRequest {
    logger.info(s"Unlink receipt from refund: $receiptId")
    for {
      userId <- userIdF
      response <- unlinkReceiptFromRefundAction(receiptId, userId)
    } yield Right(response)
  }

  private def getFile(
      receiptId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, (Array[Byte], String, String)]] = handleRequest {
    for {
      userId <- userIdF
      (bytes, filename) <- getReceiptFileAction(receiptId, userId)
      mimeType = filename.toLowerCase.split('.').lastOption match {
        case Some("jpg") | Some("jpeg") => "image/jpeg"
        case Some("png") => "image/png"
        case Some("gif") => "image/gif"
        case Some("pdf") => "application/pdf"
        case Some("webp") => "image/webp"
        case _ => "application/octet-stream"
      }
    } yield Right((bytes, s"""inline; filename="$filename"""", mimeType))
  }

  private def ignore(
      receiptId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, IgnoreReceipt.Response]] = handleRequest {
    logger.info(s"Ignore receipt: $receiptId")
    for {
      userId <- userIdF
      response <- ignoreReceiptAction(receiptId, userId)
    } yield Right(response)
  }

  private def unignore(
      receiptId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UnignoreReceipt.Response]] = handleRequest {
    logger.info(s"Unignore receipt: $receiptId")
    for {
      userId <- userIdF
      response <- unignoreReceiptAction(receiptId, userId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] =
    List(
      ReceiptsEndpoints.upload.serverLogic(upload),
      ReceiptsEndpoints.getAll.serverLogic(getAll),
      ReceiptsEndpoints.getOne.serverLogic(getOne),
      ReceiptsEndpoints.getFile.serverLogic(getFile),
      ReceiptsEndpoints.confirm.serverLogic(confirm),
      ReceiptsEndpoints.delete.serverLogic(delete),
      ReceiptsEndpoints.link.serverLogic(link),
      ReceiptsEndpoints.unlink.serverLogic(unlink),
      ReceiptsEndpoints.linkToRefund.serverLogic(linkToRefund),
      ReceiptsEndpoints.unlinkFromRefund.serverLogic(unlinkFromRefund),
      ReceiptsEndpoints.ignore.serverLogic(ignore),
      ReceiptsEndpoints.unignore.serverLogic(unignore)
    )
}
