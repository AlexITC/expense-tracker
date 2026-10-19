package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.bankstatements.*
import net.wiringbits.api.models.bankstatements.GetAllRows
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object BankStatementsEndpoints {
  private val baseEndpoint = endpoint
    .in("bank-statements")
    .tag("BankStatements")
    .errorOut(errorResponseErrorOut)

  def createDraft(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (Array[Byte], String, Future[UUID]), ErrorResponse, BankStatementDraft.Data, Any] =
    baseEndpoint.post
      .in("drafts")
      .in(byteArrayBody)
      .in(query[String]("filename"))
      .in(userAuth)
      .out(jsonBody[BankStatementDraft.Data])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary(
        "Create a draft import from a bank statement PDF (AI extraction), for the user to review before confirming"
      )

  def listDrafts(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, BankStatementDraft.Listing.Response, Any] =
    baseEndpoint.get
      .in("drafts")
      .in(userAuth)
      .out(jsonBody[BankStatementDraft.Listing.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("List the user's draft imports (the import queue)")

  def getDraft(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, BankStatementDraft.Data, Any] =
    baseEndpoint.get
      .in("drafts" / path[UUID]("draftId"))
      .in(userAuth)
      .out(jsonBody[BankStatementDraft.Data])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get one draft import with its payload")

  def updateDraft(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, BankStatementDraft.Update.Request, Future[UUID]),
    ErrorResponse,
    BankStatementDraft.Data,
    Any
  ] =
    baseEndpoint.put
      .in("drafts" / path[UUID]("draftId"))
      .in(jsonBody[BankStatementDraft.Update.Request])
      .in(userAuth)
      .out(jsonBody[BankStatementDraft.Data])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Save edits to a draft import (re-checks reconciliation)")

  def confirmDraft(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, BankStatementDraft.Confirm.Response, Any] =
    baseEndpoint.post
      .in("drafts" / path[UUID]("draftId") / "confirm")
      .in(userAuth)
      .out(jsonBody[BankStatementDraft.Confirm.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Confirm a draft into a real bank statement + rows (only when it reconciles)")

  def discardDraft(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, BankStatementDraft.Discard.Response, Any] =
    baseEndpoint.delete
      .in("drafts" / path[UUID]("draftId"))
      .in(userAuth)
      .out(jsonBody[BankStatementDraft.Discard.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Discard a draft import")

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetBankStatements.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetBankStatements.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all bank statements for the authenticated user")

  def getAllRows(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetAllRows.Response, Any] =
    baseEndpoint.get
      .in("rows")
      .in(userAuth)
      .out(jsonBody[GetAllRows.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all bank statement rows for the authenticated user")

  def getOne(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, GetBankStatement.Response, Any] =
    baseEndpoint.get
      .in(path[UUID]("bankStatementId"))
      .in(userAuth)
      .out(jsonBody[GetBankStatement.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get a bank statement with its rows")

  def regenerateAiSuggestions(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, RegenerateAiSuggestions.Response, Any] =
    baseEndpoint.post
      .in(path[UUID]("bankStatementId") / "ai-suggestions")
      .in(userAuth)
      .out(jsonBody[RegenerateAiSuggestions.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("(Re)generate the AI category/merchant suggestions for a bank statement")

  def confirmRow(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, UUID, ConfirmBankStatementRow.Request, Future[UUID]),
    ErrorResponse,
    ConfirmBankStatementRow.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "confirm")
      .in(jsonBody[ConfirmBankStatementRow.Request])
      .in(userAuth)
      .out(jsonBody[ConfirmBankStatementRow.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Confirm a single bank statement row")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteBankStatement.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("bankStatementId"))
      .in(userAuth)
      .out(jsonBody[DeleteBankStatement.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a bank statement")

  def createExpenseFromRow(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, UUID, CreateExpenseFromRow.Request, Future[UUID]),
    ErrorResponse,
    CreateExpenseFromRow.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "create-expense")
      .in(jsonBody[CreateExpenseFromRow.Request])
      .in(userAuth)
      .out(jsonBody[CreateExpenseFromRow.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create an expense from a bank statement row and link it")

  def linkRowToExpense(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, UUID, LinkRowToExpense.Request, Future[UUID]),
    ErrorResponse,
    LinkRowToExpense.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "link")
      .in(jsonBody[LinkRowToExpense.Request])
      .in(userAuth)
      .out(jsonBody[LinkRowToExpense.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Link a bank statement row to an existing expense")

  def unlinkRow(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UUID, Future[UUID]), ErrorResponse, UnlinkRow.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "link")
      .in(userAuth)
      .out(jsonBody[UnlinkRow.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Unlink a bank statement row from its expense")

  def ignoreRow(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UUID, Future[UUID]), ErrorResponse, IgnoreRow.Response, Any] =
    baseEndpoint.post
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "ignore")
      .in(userAuth)
      .out(jsonBody[IgnoreRow.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Mark a bank statement row as ignored")

  def unignoreRow(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UUID, Future[UUID]), ErrorResponse, UnignoreRow.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "ignore")
      .in(userAuth)
      .out(jsonBody[UnignoreRow.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Unmark a bank statement row as ignored")

  def unlinkRowRefund(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UUID, Future[UUID]), ErrorResponse, UnlinkRowRefund.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "create-refund")
      .in(userAuth)
      .out(jsonBody[UnlinkRowRefund.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Unlink a bank statement row from its refund")

  def createRefundFromRow(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[
    Unit,
    (UUID, UUID, CreateRefundFromRow.Request, Future[UUID]),
    ErrorResponse,
    CreateRefundFromRow.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("bankStatementId") / "rows" / path[UUID]("rowId") / "create-refund")
      .in(jsonBody[CreateRefundFromRow.Request])
      .in(userAuth)
      .out(jsonBody[CreateRefundFromRow.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create a refund from a bank statement row and link it")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(
      createDraft,
      listDrafts,
      getDraft,
      updateDraft,
      confirmDraft,
      discardDraft,
      getAll,
      getAllRows,
      getOne,
      regenerateAiSuggestions,
      confirmRow,
      delete,
      createExpenseFromRow,
      createRefundFromRow,
      unlinkRowRefund,
      linkRowToExpense,
      unlinkRow,
      ignoreRow,
      unignoreRow
    )
}
