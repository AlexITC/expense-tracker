package net.wiringbits.api

import net.wiringbits.api.endpoints.*
import net.wiringbits.api.models.*
import net.wiringbits.api.models.admin.{AdminGetUserLogs, AdminGetUsers}
import net.wiringbits.api.models.auth.{GetCurrentUser, Login, Logout}
import net.wiringbits.api.models.environmentconfig.GetEnvironmentConfig
import net.wiringbits.api.models.categories.*
import net.wiringbits.api.models.bankstatements.*
import net.wiringbits.api.models.bankstatementtemplates.*
import net.wiringbits.api.models.expenses.*
import net.wiringbits.api.models.merchants.*
import net.wiringbits.api.models.receipts.*
import net.wiringbits.api.models.refunds.*
import net.wiringbits.api.models.savings.*
import net.wiringbits.api.models.users.*
import play.api.libs.json.{Json, Reads}
import sttp.client3.*
import sttp.tapir.PublicEndpoint
import sttp.tapir.client.sttp.SttpClientInterpreter
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ApiClient {
  case class Config(serverUrl: String)
}

class ApiClient(config: ApiClient.Config)(implicit
    ex: ExecutionContext,
    sttpBackend: SttpBackend[Future, ?]
) {
  private val dummyUserId = Future.successful(UUID.fromString("887a5d77-cb5d-4d9c-b4dc-539c8aae3977"))

  private implicit val handleDummyUserId: ServerRequest => Future[UUID] = _ => dummyUserId

  private def asJson[R: Reads](strBody: String) = {
    Try {
      Json.parse(strBody).as[ErrorResponse]
    } match {
      case Success(error) => throw new RuntimeException(error.error)
      case Failure(_) =>
        Try {
          Json.parse(strBody).as[R]
        } match {
          case Success(response) => response
          case Failure(error) => throw new RuntimeException(s"Unexpected response ${error.getMessage}")
        }
    }
  }

  private val ServerAPI = sttp.model.Uri
    .parse(config.serverUrl)
    .getOrElse(throw new RuntimeException("Invalid server url"))

  private val client = SttpClientInterpreter()

  private var lastAuthResponse = Option.empty[Response[?]]

  private def unsafeSetLoginResponse(response: Response[?]): Unit = synchronized {
    lastAuthResponse = Some(response)
  }

  private def unsafeRemoveLoginResponse(): Unit = synchronized {
    lastAuthResponse = None
  }

  private def handleRequest[I, O](endpoint: PublicEndpoint[I, ErrorResponse, O, Any], request: I): Future[O] = {
    val savedCookies = lastAuthResponse.map(_.unsafeCookies).getOrElse(Seq.empty)

    client
      .toRequestThrowDecodeFailures(endpoint, Some(ServerAPI))
      .apply(request)
      .cookies(savedCookies)
      .send(sttpBackend)
      .map(_.body)
      .map {
        case Left(error) => throw new RuntimeException(error.error)
        case Right(response) => response
      }
  }

  def createUser(request: CreateUser.Request): Future[CreateUser.Response] =
    handleRequest(UsersEndpoints.create, request)

  def verifyEmail(request: VerifyEmail.Request): Future[VerifyEmail.Response] =
    handleRequest(UsersEndpoints.verifyEmail, request)

  def forgotPassword(request: ForgotPassword.Request): Future[ForgotPassword.Response] =
    handleRequest(UsersEndpoints.forgotPassword, request)

  def resetPassword(request: ResetPassword.Request): Future[ResetPassword.Response] =
    handleRequest(UsersEndpoints.resetPassword, request)

  def currentUser: Future[GetCurrentUser.Response] =
    handleRequest(AuthEndpoints.getCurrentUser, dummyUserId)

  def updateUser(request: UpdateUser.Request): Future[UpdateUser.Response] =
    handleRequest(UsersEndpoints.update, (request, dummyUserId))

  def updatePassword(request: UpdatePassword.Request): Future[UpdatePassword.Response] =
    handleRequest(UsersEndpoints.updatePassword, (request, dummyUserId))

  def getUserLogs: Future[GetUserLogs.Response] =
    handleRequest(UsersEndpoints.getLogs, dummyUserId)

  def createTelegramLinkToken: Future[CreateTelegramLinkToken.Response] =
    handleRequest(UsersEndpoints.createTelegramLinkToken, dummyUserId)

  def createMcpToken: Future[CreateMcpToken.Response] =
    handleRequest(UsersEndpoints.createMcpToken, dummyUserId)

  def adminGetUserLogs(userId: UUID): Future[AdminGetUserLogs.Response] =
    handleRequest(AdminEndpoints.getUserLogsEndpoint, ("_", userId, ""))

  def adminGetUsers: Future[AdminGetUsers.Response] =
    handleRequest(AdminEndpoints.getUsersEndpoint, ("_", ""))

  def getEnvironmentConfig: Future[GetEnvironmentConfig.Response] =
    handleRequest(EnvironmentConfigEndpoints.getEnvironmentConfig, ())

  def sendEmailVerificationToken(
      request: SendEmailVerificationToken.Request
  ): Future[SendEmailVerificationToken.Response] =
    handleRequest(UsersEndpoints.sendEmailVerificationToken, request)

  def getCategories: Future[GetCategories.Response] =
    handleRequest(CategoriesEndpoints.getAll, dummyUserId)

  def createCategory(request: CreateCategory.Request): Future[CreateCategory.Response] =
    handleRequest(CategoriesEndpoints.create, (request, dummyUserId))

  def updateCategory(categoryId: UUID, request: UpdateCategory.Request): Future[UpdateCategory.Response] =
    handleRequest(CategoriesEndpoints.update, (categoryId, request, dummyUserId))

  def deleteCategory(categoryId: UUID): Future[DeleteCategory.Response] =
    handleRequest(CategoriesEndpoints.delete, (categoryId, dummyUserId))

  def getExpenses: Future[GetExpenses.Response] =
    handleRequest(ExpensesEndpoints.getAll, dummyUserId)

  def createExpense(request: CreateExpense.Request): Future[CreateExpense.Response] =
    handleRequest(ExpensesEndpoints.create, (request, dummyUserId))

  def updateExpense(expenseId: UUID, request: UpdateExpense.Request): Future[UpdateExpense.Response] =
    handleRequest(ExpensesEndpoints.update, (expenseId, request, dummyUserId))

  def deleteExpense(expenseId: UUID): Future[DeleteExpense.Response] =
    handleRequest(ExpensesEndpoints.delete, (expenseId, dummyUserId))

  def getMerchants: Future[GetMerchants.Response] =
    handleRequest(MerchantsEndpoints.getAll, dummyUserId)

  def createMerchant(request: CreateMerchant.Request): Future[CreateMerchant.Response] =
    handleRequest(MerchantsEndpoints.create, (request, dummyUserId))

  def updateMerchant(merchantId: UUID, request: UpdateMerchant.Request): Future[UpdateMerchant.Response] =
    handleRequest(MerchantsEndpoints.update, (merchantId, request, dummyUserId))

  def deleteMerchant(merchantId: UUID): Future[DeleteMerchant.Response] =
    handleRequest(MerchantsEndpoints.delete, (merchantId, dummyUserId))

  def getPendingMerchants: Future[GetPendingMerchants.Response] =
    handleRequest(MerchantsEndpoints.getPending, dummyUserId)

  def reviewMerchant(merchantId: UUID, request: ReviewMerchant.Request): Future[ReviewMerchant.Response] =
    handleRequest(MerchantsEndpoints.review, (merchantId, request, dummyUserId))

  def markMerchantAsDuplicate(
      merchantId: UUID,
      request: MarkMerchantAsDuplicate.Request
  ): Future[MarkMerchantAsDuplicate.Response] =
    handleRequest(MerchantsEndpoints.markAsDuplicate, (merchantId, request, dummyUserId))

  def getImportRules: Future[GetImportRules.Response] =
    handleRequest(ImportRulesEndpoints.getAll, dummyUserId)

  def createImportRule(request: CreateImportRule.Request): Future[CreateImportRule.Response] =
    handleRequest(ImportRulesEndpoints.create, (request, dummyUserId))

  def updateImportRule(ruleId: UUID, request: UpdateImportRule.Request): Future[UpdateImportRule.Response] =
    handleRequest(ImportRulesEndpoints.update, (ruleId, request, dummyUserId))

  def deleteImportRule(ruleId: UUID): Future[DeleteImportRule.Response] =
    handleRequest(ImportRulesEndpoints.delete, (ruleId, dummyUserId))

  def uploadReceipt(bytes: Array[Byte], kind: String, filename: String): Future[UploadReceipt.Response] =
    handleRequest(ReceiptsEndpoints.upload, (bytes, kind, filename, dummyUserId))

  def getReceipts: Future[GetReceipts.Response] =
    handleRequest(ReceiptsEndpoints.getAll, dummyUserId)

  def getReceipt(receiptId: UUID): Future[GetReceipt.Response] =
    handleRequest(ReceiptsEndpoints.getOne, (receiptId, dummyUserId))

  def getReceiptFile(receiptId: UUID): Future[(Array[Byte], String, String)] =
    handleRequest(ReceiptsEndpoints.getFile, (receiptId, dummyUserId))

  def confirmReceipt(receiptId: UUID, request: ConfirmReceipt.Request): Future[ConfirmReceipt.Response] =
    handleRequest(ReceiptsEndpoints.confirm, (receiptId, request, dummyUserId))

  def deleteReceipt(receiptId: UUID): Future[DeleteReceipt.Response] =
    handleRequest(ReceiptsEndpoints.delete, (receiptId, dummyUserId))

  def linkReceiptToExpense(
      receiptId: UUID,
      request: LinkReceiptToExpense.Request
  ): Future[LinkReceiptToExpense.Response] =
    handleRequest(ReceiptsEndpoints.link, (receiptId, request, dummyUserId))

  def unlinkReceipt(receiptId: UUID): Future[UnlinkReceipt.Response] =
    handleRequest(ReceiptsEndpoints.unlink, (receiptId, dummyUserId))

  def ignoreReceipt(receiptId: UUID): Future[IgnoreReceipt.Response] =
    handleRequest(ReceiptsEndpoints.ignore, (receiptId, dummyUserId))

  def unignoreReceipt(receiptId: UUID): Future[UnignoreReceipt.Response] =
    handleRequest(ReceiptsEndpoints.unignore, (receiptId, dummyUserId))

  def createBankStatementDraft(bytes: Array[Byte], filename: String): Future[BankStatementDraft.Data] =
    handleRequest(BankStatementsEndpoints.createDraft, (bytes, filename, dummyUserId))

  def listBankStatementDrafts: Future[BankStatementDraft.Listing.Response] =
    handleRequest(BankStatementsEndpoints.listDrafts, dummyUserId)

  def getBankStatementDraft(draftId: UUID): Future[BankStatementDraft.Data] =
    handleRequest(BankStatementsEndpoints.getDraft, (draftId, dummyUserId))

  def updateBankStatementDraft(
      draftId: UUID,
      request: BankStatementDraft.Update.Request
  ): Future[BankStatementDraft.Data] =
    handleRequest(BankStatementsEndpoints.updateDraft, (draftId, request, dummyUserId))

  def confirmBankStatementDraft(draftId: UUID): Future[BankStatementDraft.Confirm.Response] =
    handleRequest(BankStatementsEndpoints.confirmDraft, (draftId, dummyUserId))

  def discardBankStatementDraft(draftId: UUID): Future[BankStatementDraft.Discard.Response] =
    handleRequest(BankStatementsEndpoints.discardDraft, (draftId, dummyUserId))

  def getBankStatements: Future[GetBankStatements.Response] =
    handleRequest(BankStatementsEndpoints.getAll, dummyUserId)

  def getAllBankStatementRows: Future[GetAllRows.Response] =
    handleRequest(BankStatementsEndpoints.getAllRows, dummyUserId)

  def getBankStatement(bankStatementId: UUID): Future[GetBankStatement.Response] =
    handleRequest(BankStatementsEndpoints.getOne, (bankStatementId, dummyUserId))

  def confirmBankStatementRow(
      bankStatementId: UUID,
      rowId: UUID,
      request: ConfirmBankStatementRow.Request
  ): Future[ConfirmBankStatementRow.Response] =
    handleRequest(BankStatementsEndpoints.confirmRow, (bankStatementId, rowId, request, dummyUserId))

  def deleteBankStatement(bankStatementId: UUID): Future[DeleteBankStatement.Response] =
    handleRequest(BankStatementsEndpoints.delete, (bankStatementId, dummyUserId))

  def createExpenseFromRow(
      bankStatementId: UUID,
      rowId: UUID,
      request: CreateExpenseFromRow.Request
  ): Future[CreateExpenseFromRow.Response] =
    handleRequest(BankStatementsEndpoints.createExpenseFromRow, (bankStatementId, rowId, request, dummyUserId))

  def linkRowToExpense(
      bankStatementId: UUID,
      rowId: UUID,
      request: LinkRowToExpense.Request
  ): Future[LinkRowToExpense.Response] =
    handleRequest(BankStatementsEndpoints.linkRowToExpense, (bankStatementId, rowId, request, dummyUserId))

  def unlinkRow(bankStatementId: UUID, rowId: UUID): Future[UnlinkRow.Response] =
    handleRequest(BankStatementsEndpoints.unlinkRow, (bankStatementId, rowId, dummyUserId))

  def ignoreRow(bankStatementId: UUID, rowId: UUID): Future[IgnoreRow.Response] =
    handleRequest(BankStatementsEndpoints.ignoreRow, (bankStatementId, rowId, dummyUserId))

  def unignoreRow(bankStatementId: UUID, rowId: UUID): Future[UnignoreRow.Response] =
    handleRequest(BankStatementsEndpoints.unignoreRow, (bankStatementId, rowId, dummyUserId))

  def createRefundFromRow(
      bankStatementId: UUID,
      rowId: UUID,
      request: CreateRefundFromRow.Request
  ): Future[CreateRefundFromRow.Response] =
    handleRequest(BankStatementsEndpoints.createRefundFromRow, (bankStatementId, rowId, request, dummyUserId))

  def unlinkRowRefund(bankStatementId: UUID, rowId: UUID): Future[UnlinkRowRefund.Response] =
    handleRequest(BankStatementsEndpoints.unlinkRowRefund, (bankStatementId, rowId, dummyUserId))

  def getRefunds: Future[GetRefunds.Response] =
    handleRequest(RefundsEndpoints.getAll, dummyUserId)

  def createRefund(request: CreateRefund.Request): Future[CreateRefund.Response] =
    handleRequest(RefundsEndpoints.create, (request, dummyUserId))

  def updateRefund(refundId: UUID, request: UpdateRefund.Request): Future[UpdateRefund.Response] =
    handleRequest(RefundsEndpoints.update, (refundId, request, dummyUserId))

  def deleteRefund(refundId: UUID): Future[DeleteRefund.Response] =
    handleRequest(RefundsEndpoints.delete, (refundId, dummyUserId))

  def linkRefundToExpense(
      refundId: UUID,
      request: LinkRefundToExpense.Request
  ): Future[LinkRefundToExpense.Response] =
    handleRequest(RefundsEndpoints.linkToExpense, (refundId, request, dummyUserId))

  def unlinkRefundFromExpense(refundId: UUID): Future[UnlinkRefundFromExpense.Response] =
    handleRequest(RefundsEndpoints.unlinkFromExpense, (refundId, dummyUserId))

  def linkReceiptToRefund(
      receiptId: UUID,
      request: LinkReceiptToRefund.Request
  ): Future[LinkReceiptToRefund.Response] =
    handleRequest(ReceiptsEndpoints.linkToRefund, (receiptId, request, dummyUserId))

  def unlinkReceiptFromRefund(receiptId: UUID): Future[UnlinkReceiptFromRefund.Response] =
    handleRequest(ReceiptsEndpoints.unlinkFromRefund, (receiptId, dummyUserId))

  def getSavingsGoals: Future[GetSavingsGoals.Response] =
    handleRequest(SavingsEndpoints.getGoals, dummyUserId)

  def createSavingsGoal(request: CreateSavingsGoal.Request): Future[CreateSavingsGoal.Response] =
    handleRequest(SavingsEndpoints.createGoal, (request, dummyUserId))

  def updateSavingsGoal(goalId: UUID, request: UpdateSavingsGoal.Request): Future[UpdateSavingsGoal.Response] =
    handleRequest(SavingsEndpoints.updateGoal, (goalId, request, dummyUserId))

  def deleteSavingsGoal(goalId: UUID): Future[DeleteSavingsGoal.Response] =
    handleRequest(SavingsEndpoints.deleteGoal, (goalId, dummyUserId))

  def getSavingsAllocations(goalId: UUID): Future[GetSavingsAllocations.Response] =
    handleRequest(SavingsEndpoints.getAllocations, (goalId, dummyUserId))

  def createSavingsAllocation(
      goalId: UUID,
      request: CreateSavingsAllocation.Request
  ): Future[CreateSavingsAllocation.Response] =
    handleRequest(SavingsEndpoints.createAllocation, (goalId, request, dummyUserId))

  def updateSavingsAllocation(
      goalId: UUID,
      allocationId: UUID,
      request: UpdateSavingsAllocation.Request
  ): Future[UpdateSavingsAllocation.Response] =
    handleRequest(SavingsEndpoints.updateAllocation, (goalId, allocationId, request, dummyUserId))

  def deleteSavingsAllocation(goalId: UUID, allocationId: UUID): Future[DeleteSavingsAllocation.Response] =
    handleRequest(SavingsEndpoints.deleteAllocation, (goalId, allocationId, dummyUserId))

  def getSavingsEstimate(merchantId: UUID): Future[GetSavingsEstimate.Response] =
    handleRequest(SavingsEndpoints.getEstimate, (merchantId, dummyUserId))

  def aiExtractBankStatement(
      request: AiExtractBankStatement.Request
  ): Future[AiExtractBankStatement.Response] =
    handleRequest(BankStatementTemplatesEndpoints.aiExtract, (request, dummyUserId))

  def login(request: Login.Request): Future[Login.Response] =
    client
      .toRequestThrowDecodeFailures(AuthEndpoints.login, Some(ServerAPI))
      .apply(request)
      .response(asStringAlways)
      .send(sttpBackend)
      .map { response =>
        unsafeSetLoginResponse(response)
        response.body
      }
      .map(asJson[Login.Response])

  def logout: Future[Logout.Response] =
    client
      .toRequestThrowDecodeFailures(AuthEndpoints.logout, Some(ServerAPI))
      .apply(dummyUserId)
      .response(asStringAlways)
      .send(sttpBackend)
      .map { response =>
        unsafeRemoveLoginResponse()
        response.body
      }
      .map(asJson[Logout.Response])
}
