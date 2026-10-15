package net.wiringbits.api.endpoints

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
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object ReceiptsEndpoints {
  private val baseEndpoint = endpoint
    .in("receipts")
    .tag("Receipts")
    .errorOut(errorResponseErrorOut)

  def upload(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (Array[Byte], String, String, Future[UUID]), ErrorResponse, UploadReceipt.Response, Any] =
    baseEndpoint.post
      .in(byteArrayBody)
      .in(query[String]("kind"))
      .in(query[String]("filename"))
      .in(userAuth)
      .out(jsonBody[UploadReceipt.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Upload a receipt or invoice for the authenticated user")

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetReceipts.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetReceipts.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all receipts for the authenticated user")

  def getOne(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, GetReceipt.Response, Any] =
    baseEndpoint.get
      .in(path[UUID]("receiptId"))
      .in(userAuth)
      .out(jsonBody[GetReceipt.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get a receipt for the authenticated user")

  def confirm(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, ConfirmReceipt.Request, Future[UUID]), ErrorResponse, ConfirmReceipt.Response, Any] =
    baseEndpoint.post
      .in(path[UUID]("receiptId") / "confirm")
      .in(jsonBody[ConfirmReceipt.Request])
      .in(userAuth)
      .out(jsonBody[ConfirmReceipt.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Confirm a parsed receipt, creating an expense")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteReceipt.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("receiptId"))
      .in(userAuth)
      .out(jsonBody[DeleteReceipt.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a receipt")

  def link(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, LinkReceiptToExpense.Request, Future[UUID]),
    ErrorResponse,
    LinkReceiptToExpense.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("receiptId") / "link")
      .in(jsonBody[LinkReceiptToExpense.Request])
      .in(userAuth)
      .out(jsonBody[LinkReceiptToExpense.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Link a receipt to an existing expense")

  def unlink(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, UnlinkReceipt.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("receiptId") / "link")
      .in(userAuth)
      .out(jsonBody[UnlinkReceipt.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Unlink a receipt from its expense")

  def linkToRefund(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, LinkReceiptToRefund.Request, Future[UUID]),
    ErrorResponse,
    LinkReceiptToRefund.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("receiptId") / "link-refund")
      .in(jsonBody[LinkReceiptToRefund.Request])
      .in(userAuth)
      .out(jsonBody[LinkReceiptToRefund.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Link a receipt to an existing refund")

  def unlinkFromRefund(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, UnlinkReceiptFromRefund.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("receiptId") / "link-refund")
      .in(userAuth)
      .out(jsonBody[UnlinkReceiptFromRefund.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Unlink a receipt from its refund")

  def getFile(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, (Array[Byte], String, String), Any] =
    baseEndpoint.get
      .in(path[UUID]("receiptId") / "file")
      .in(userAuth)
      .out(byteArrayBody)
      .out(header[String]("Content-Disposition"))
      .out(header[String]("Content-Type"))
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get the raw file bytes of a receipt from storage")

  def ignore(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, IgnoreReceipt.Response, Any] =
    baseEndpoint.post
      .in(path[UUID]("receiptId") / "ignore")
      .in(userAuth)
      .out(jsonBody[IgnoreReceipt.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Ignore a receipt (must not be linked to an expense)")

  def unignore(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, UnignoreReceipt.Response, Any] =
    baseEndpoint.post
      .in(path[UUID]("receiptId") / "unignore")
      .in(userAuth)
      .out(jsonBody[UnignoreReceipt.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Un-ignore a previously ignored receipt")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(
      upload,
      getAll,
      getOne,
      confirm,
      delete,
      link,
      unlink,
      linkToRefund,
      unlinkFromRefund,
      getFile,
      ignore,
      unignore
    )
}
