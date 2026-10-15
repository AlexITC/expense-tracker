package controllers

import net.wiringbits.actions.bankstatements.*
import net.wiringbits.api.endpoints.BankStatementsEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.bankstatements.*
import net.wiringbits.api.models.bankstatements.GetAllRows
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class BankStatementsController @Inject() (
    createBankStatementDraftAction: CreateBankStatementDraftAction,
    listBankStatementDraftsAction: ListBankStatementDraftsAction,
    getBankStatementDraftAction: GetBankStatementDraftAction,
    updateBankStatementDraftAction: UpdateBankStatementDraftAction,
    confirmBankStatementDraftAction: ConfirmBankStatementDraftAction,
    discardBankStatementDraftAction: DiscardBankStatementDraftAction,
    getBankStatementsAction: GetBankStatementsAction,
    getAllRowsAction: GetAllRowsAction,
    getBankStatementAction: GetBankStatementAction,
    confirmBankStatementRowAction: ConfirmBankStatementRowAction,
    deleteBankStatementAction: DeleteBankStatementAction,
    createExpenseFromRowAction: CreateExpenseFromRowAction,
    linkRowToExpenseAction: LinkRowToExpenseAction,
    unlinkRowAction: UnlinkRowAction,
    ignoreRowAction: IgnoreRowAction,
    unignoreRowAction: UnignoreRowAction,
    createRefundFromRowAction: CreateRefundFromRowAction,
    unlinkRowRefundAction: UnlinkRowRefundAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def createDraft(
      bytes: Array[Byte],
      filename: String,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, BankStatementDraft.Data]] = handleRequest {
    logger.info(s"Create statement draft: filename=$filename, size=${bytes.length}")
    for {
      userId <- userIdF
      response <- createBankStatementDraftAction(userId, bytes, filename)
    } yield Right(response)
  }

  private def listDrafts(userIdF: Future[UUID]): Future[Either[ErrorResponse, BankStatementDraft.Listing.Response]] =
    handleRequest {
      for {
        userId <- userIdF
        response <- listBankStatementDraftsAction(userId)
      } yield Right(response)
    }

  private def getDraft(
      draftId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, BankStatementDraft.Data]] = handleRequest {
    for {
      userId <- userIdF
      response <- getBankStatementDraftAction(draftId, userId)
    } yield Right(response)
  }

  private def updateDraft(
      draftId: UUID,
      request: BankStatementDraft.Update.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, BankStatementDraft.Data]] = handleRequest {
    for {
      userId <- userIdF
      response <- updateBankStatementDraftAction(draftId, userId, request)
    } yield Right(response)
  }

  private def confirmDraft(
      draftId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, BankStatementDraft.Confirm.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- confirmBankStatementDraftAction(draftId, userId)
    } yield Right(response)
  }

  private def discardDraft(
      draftId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, BankStatementDraft.Discard.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- discardBankStatementDraftAction(draftId, userId)
    } yield Right(response)
  }

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetBankStatements.Response]] =
    handleRequest {
      for {
        userId <- userIdF
        response <- getBankStatementsAction(userId)
      } yield Right(response)
    }

  private def getAllRows(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetAllRows.Response]] =
    handleRequest {
      for {
        userId <- userIdF
        response <- getAllRowsAction(userId)
      } yield Right(response)
    }

  private def getOne(
      bankStatementId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, GetBankStatement.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getBankStatementAction(bankStatementId, userId)
    } yield Right(response)
  }

  private def confirmRow(
      bankStatementId: UUID,
      rowId: UUID,
      request: ConfirmBankStatementRow.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, ConfirmBankStatementRow.Response]] = handleRequest {
    logger.info(s"Confirm bank statement row: stmt=$bankStatementId row=$rowId")
    for {
      userId <- userIdF
      response <- confirmBankStatementRowAction(bankStatementId, rowId, userId, request)
    } yield Right(response)
  }

  private def delete(
      bankStatementId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteBankStatement.Response]] = handleRequest {
    logger.info(s"Delete bank statement: $bankStatementId")
    for {
      userId <- userIdF
      response <- deleteBankStatementAction(bankStatementId, userId)
    } yield Right(response)
  }

  private def createExpenseFromRow(
      bankStatementId: UUID,
      rowId: UUID,
      request: CreateExpenseFromRow.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateExpenseFromRow.Response]] = handleRequest {
    logger.info(s"Create expense from bank statement row: stmt=$bankStatementId row=$rowId")
    for {
      userId <- userIdF
      response <- createExpenseFromRowAction(bankStatementId, rowId, userId, request)
    } yield Right(response)
  }

  private def linkRowToExpense(
      bankStatementId: UUID,
      rowId: UUID,
      request: LinkRowToExpense.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, LinkRowToExpense.Response]] = handleRequest {
    logger.info(s"Link bank statement row to expense: stmt=$bankStatementId row=$rowId expense=${request.expenseId}")
    for {
      userId <- userIdF
      response <- linkRowToExpenseAction(bankStatementId, rowId, userId, request)
    } yield Right(response)
  }

  private def unlinkRow(
      bankStatementId: UUID,
      rowId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UnlinkRow.Response]] = handleRequest {
    logger.info(s"Unlink bank statement row: stmt=$bankStatementId row=$rowId")
    for {
      userId <- userIdF
      response <- unlinkRowAction(bankStatementId, rowId, userId)
    } yield Right(response)
  }

  private def ignoreRow(
      bankStatementId: UUID,
      rowId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, IgnoreRow.Response]] = handleRequest {
    logger.info(s"Ignore bank statement row: stmt=$bankStatementId row=$rowId")
    for {
      userId <- userIdF
      response <- ignoreRowAction(bankStatementId, rowId, userId)
    } yield Right(response)
  }

  private def unignoreRow(
      bankStatementId: UUID,
      rowId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UnignoreRow.Response]] = handleRequest {
    logger.info(s"Unignore bank statement row: stmt=$bankStatementId row=$rowId")
    for {
      userId <- userIdF
      response <- unignoreRowAction(bankStatementId, rowId, userId)
    } yield Right(response)
  }

  private def createRefundFromRow(
      bankStatementId: UUID,
      rowId: UUID,
      request: CreateRefundFromRow.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateRefundFromRow.Response]] = handleRequest {
    logger.info(s"Create refund from bank statement row: stmt=$bankStatementId row=$rowId")
    for {
      userId <- userIdF
      response <- createRefundFromRowAction(bankStatementId, rowId, userId, request)
    } yield Right(response)
  }

  private def unlinkRowRefund(
      bankStatementId: UUID,
      rowId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UnlinkRowRefund.Response]] = handleRequest {
    logger.info(s"Unlink refund from bank statement row: stmt=$bankStatementId row=$rowId")
    for {
      userId <- userIdF
      response <- unlinkRowRefundAction(bankStatementId, rowId, userId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] =
    List(
      BankStatementsEndpoints.createDraft.serverLogic(createDraft),
      BankStatementsEndpoints.listDrafts.serverLogic(listDrafts),
      BankStatementsEndpoints.getDraft.serverLogic(getDraft),
      BankStatementsEndpoints.updateDraft.serverLogic(updateDraft),
      BankStatementsEndpoints.confirmDraft.serverLogic(confirmDraft),
      BankStatementsEndpoints.discardDraft.serverLogic(discardDraft),
      BankStatementsEndpoints.getAll.serverLogic(getAll),
      BankStatementsEndpoints.getAllRows.serverLogic(getAllRows),
      BankStatementsEndpoints.getOne.serverLogic(getOne),
      BankStatementsEndpoints.confirmRow.serverLogic(confirmRow),
      BankStatementsEndpoints.delete.serverLogic(delete),
      BankStatementsEndpoints.createExpenseFromRow.serverLogic(createExpenseFromRow),
      BankStatementsEndpoints.createRefundFromRow.serverLogic(createRefundFromRow),
      BankStatementsEndpoints.unlinkRowRefund.serverLogic(unlinkRowRefund),
      BankStatementsEndpoints.linkRowToExpense.serverLogic(linkRowToExpense),
      BankStatementsEndpoints.unlinkRow.serverLogic(unlinkRow),
      BankStatementsEndpoints.ignoreRow.serverLogic(ignoreRow),
      BankStatementsEndpoints.unignoreRow.serverLogic(unignoreRow)
    )
}
