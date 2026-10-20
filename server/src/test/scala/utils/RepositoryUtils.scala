package utils

import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.common.models.{Email, Name}
import net.wiringbits.core.RepositoryComponents
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.daos.{BackgroundJobDAO, BankStatementDAO, BankStatementRowDAO}
import net.wiringbits.repositories.models.{
  BackgroundJobData,
  BankStatement,
  BankStatementRow,
  Category,
  Document,
  Expense,
  ExpenseCategorySplit,
  Merchant,
  Receipt,
  Refund,
  SavingsAllocation,
  SavingsGoal,
  TextractCache,
  TextractJob,
  User,
  UserLog,
  UserToken,
  UserTokenType
}
import org.scalatest.concurrent.ScalaFutures.*

import java.time.{Instant, LocalDate}
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}

trait RepositoryUtils {
  val sampleStatementMetadata: BankStatement.Metadata =
    BankStatement.Metadata(
      bankName = "Test Bank",
      productType = "credit",
      currency = "USD",
      accountLast4 = "0000",
      periodStart = LocalDate.of(2026, 1, 1),
      periodEnd = LocalDate.of(2026, 1, 31)
    )

  val backgroundJobPayload: BackgroundJobPayload.SendEmail =
    BackgroundJobPayload.SendEmail(Email.trusted("sample@wiringbits.net"), subject = "Test message", body = "it works")

  def createBackgroundJobData(
      id: UUID = UUID.randomUUID(),
      backgroundJobType: BackgroundJobType = BackgroundJobType.SendEmail,
      status: BackgroundJobStatus = BackgroundJobStatus.Pending,
      payload: BackgroundJobPayload = backgroundJobPayload,
      executeAt: Instant = Instant.now()
  )(using repositories: RepositoryComponents): BackgroundJobData.Create = {
    val createRequest = BackgroundJobData.Create(
      id = id,
      `type` = backgroundJobType,
      payload = payload,
      status = status,
      executeAt = executeAt,
      createdAt = Instant.now(),
      updatedAt = Instant.now()
    )

    repositories.database.withConnection { implicit conn =>
      BackgroundJobDAO.create(createRequest)
    }

    createRequest
  }

  def createUser(
      email: Email = Email.trusted("hello@wiringbits.net")
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[User.CreateUser] = {
    val createRequest = User.CreateUser(
      id = UUID.randomUUID(),
      email = email,
      name = Name.trusted("Sample"),
      hashedPassword = "password",
      verifyEmailToken = "token"
    )

    for {
      _ <- repository.users.create(createRequest)
    } yield createRequest
  }

  def createUserLog(
      userId: UUID
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[UserLog.CreateUserLog] = {
    val createRequest =
      UserLog.CreateUserLog(userLogId = UUID.randomUUID(), userId = userId, message = "test")

    for {
      _ <- repository.userLogs.create(createRequest)
    } yield createRequest
  }

  def createUserLog(
      userId: UUID,
      message: String
  )(using repository: RepositoryComponents): Future[Unit] = {
    repository.userLogs.create(userId, message)
  }

  def createToken(
      userId: UUID
  )(using @unused ec: ExecutionContext, repository: RepositoryComponents): Future[UserToken.Create] = {
    val tokenRequest =
      UserToken.Create(
        id = UUID.randomUUID(),
        token = "test",
        tokenType = UserTokenType.ResetPassword,
        createdAt = Instant.now(),
        expiresAt = Instant.now.plus(2, ChronoUnit.DAYS),
        userId = userId
      )

    for {
      _ <- repository.userTokens.create(tokenRequest)
    } yield tokenRequest
  }

  def createCategory(
      userId: UUID,
      name: String = "Test Category"
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[Category.Create] = {
    val now = Instant.now()
    val createRequest = Category.Create(
      id = UUID.randomUUID(),
      userId = userId,
      name = name,
      createdAt = now,
      updatedAt = now
    )

    for {
      _ <- repository.categories.create(createRequest)
    } yield createRequest
  }

  def createMerchant(
      userId: UUID,
      name: String = "Test Merchant",
      notes: Option[String] = None,
      categoryId: Option[UUID] = None
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[Merchant.Create] = {
    val now = Instant.now()
    val createRequest = Merchant.Create(
      id = UUID.randomUUID(),
      userId = userId,
      name = name,
      notes = notes,
      categoryId = categoryId,
      taxId = None,
      reviewed = true,
      createdAt = now,
      updatedAt = now
    )

    for {
      _ <- repository.merchants.create(createRequest)
    } yield createRequest
  }

  def createExpense(
      userId: UUID,
      amount: BigDecimal = BigDecimal("50.00"),
      date: LocalDate = LocalDate.of(2024, 1, 15),
      merchantId: Option[UUID] = None,
      notes: Option[String] = None,
      splits: List[ExpenseCategorySplit.Create] = Nil
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[Expense.Create] = {
    val now = Instant.now()
    val createRequest = Expense.Create(
      id = UUID.randomUUID(),
      userId = userId,
      amount = amount,
      date = date,
      time = None,
      merchantId = merchantId,
      notes = notes,
      createdAt = now,
      updatedAt = now
    )

    for {
      _ <- repository.expenses.create(createRequest, splits)
    } yield createRequest
  }

  def createRefund(
      userId: UUID,
      amount: BigDecimal = BigDecimal("50.00"),
      date: LocalDate = LocalDate.of(2024, 1, 15),
      merchantId: Option[UUID] = None,
      categoryId: Option[UUID] = None,
      expenseId: Option[UUID] = None,
      notes: Option[String] = None
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[Refund.Create] = {
    val now = Instant.now()
    val createRequest = Refund.Create(
      id = UUID.randomUUID(),
      userId = userId,
      amount = amount,
      date = date,
      merchantId = merchantId,
      categoryId = categoryId,
      expenseId = expenseId,
      notes = notes,
      createdAt = now,
      updatedAt = now
    )
    for {
      _ <- repository.refunds.create(createRequest)
    } yield createRequest
  }

  def createSavingsGoal(
      userId: UUID,
      name: String = "Test Goal",
      targetAmount: Option[BigDecimal] = None
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[SavingsGoal.Create] = {
    val now = Instant.now()
    val createRequest = SavingsGoal.Create(
      id = UUID.randomUUID(),
      userId = userId,
      name = name,
      targetAmount = targetAmount,
      createdAt = now,
      updatedAt = now
    )
    for {
      _ <- repository.savings.createGoal(createRequest)
    } yield createRequest
  }

  def createSavingsAllocation(
      userId: UUID,
      goalId: UUID,
      amount: BigDecimal = BigDecimal("100.00"),
      date: LocalDate = LocalDate.of(2024, 1, 15),
      note: Option[String] = None,
      merchantId: Option[UUID] = None
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[SavingsAllocation.Create] = {
    val now = Instant.now()
    val createRequest = SavingsAllocation.Create(
      id = UUID.randomUUID(),
      userId = userId,
      goalId = goalId,
      amount = amount,
      date = date,
      note = note,
      merchantId = merchantId,
      createdAt = now,
      updatedAt = now
    )
    for {
      _ <- repository.savings.createAllocation(createRequest)
    } yield createRequest
  }

  def createImportRule(
      userId: UUID,
      pattern: String = "test-pattern",
      action: ImportRuleAction = ImportRuleAction.Ignore,
      merchantId: Option[UUID] = None
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[UUID] = {
    repository.importRules.create(userId, action, pattern, merchantId)
  }

  /** Insert a bank statement (+ optional rows) directly via the DAOs. Lives here, not on the repository, because only
    * tests create a statement outside the draft-confirm flow. `extractionInfo` is always None — a statement made
    * outside the AI import has no extraction provenance.
    */
  def createStatement(
      stmt: BankStatement.Create,
      metadata: BankStatement.Metadata,
      rows: List[BankStatementRow.Create]
  )(using repositories: RepositoryComponents, ec: ExecutionContext): Future[Unit] =
    Future {
      repositories.database.withTransaction { implicit conn =>
        BankStatementDAO.create(stmt, metadata, extractionInfo = None)
        BankStatementRowDAO.createAll(rows)
      }
    }

  def createBankStatement(
      userId: UUID
  )(using repository: RepositoryComponents)(using ec: ExecutionContext): Future[BankStatement.Create] = {
    val now = Instant.now()
    for {
      doc <- createDocument(userId, sha256 = UUID.randomUUID().toString, kind = DocumentKind.BankStatementPdf)
      stmt = BankStatement.Create(
        id = UUID.randomUUID(),
        documentId = doc.id,
        userId = userId,
        createdAt = now,
        updatedAt = now
      )
      _ <- createStatement(stmt, sampleStatementMetadata, Nil)
    } yield stmt
  }

  def createTextractJob(
      userId: UUID,
      documentId: UUID
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[TextractJob.Create] = {
    val now = Instant.now()
    val createRequest = TextractJob.Create(
      id = UUID.randomUUID(),
      userId = userId,
      documentId = documentId,
      apiType = "textract",
      jobId = "test-job-id",
      status = TextractJob.Status.PendingStart,
      createdAt = now,
      updatedAt = now
    )
    for {
      _ <- repository.textractJobs.create(createRequest)
    } yield createRequest
  }

  def createReceipt(
      userId: UUID
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[Receipt.Create] = {
    val now = Instant.now()
    val docId = UUID.randomUUID()
    val document = Document.Create(
      id = docId,
      userId = userId,
      sha256 = "sha-" + docId.toString,
      storageKey = "",
      kind = DocumentKind.Receipt,
      filename = "receipt.pdf",
      status = DocumentStatus.Ingested,
      createdAt = now,
      updatedAt = now
    )
    val receipt = Receipt.Create(
      id = UUID.randomUUID(),
      userId = userId,
      documentId = docId,
      manuallyEdited = false,
      createdAt = now,
      updatedAt = now
    )
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ProcessDocument,
      payload = BackgroundJobPayload.ProcessDocument(docId),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
    repository.receipts.createWithDocument(document, receipt, job).map(_ => receipt)
  }

  def createDocument(
      userId: UUID,
      sha256: String = "abc123",
      storageKey: String = "",
      kind: DocumentKind = DocumentKind.Receipt,
      filename: String = "test.pdf",
      status: DocumentStatus = DocumentStatus.Ingested
  )(using repository: RepositoryComponents)(using @unused ec: ExecutionContext): Future[Document.Create] = {
    val now = Instant.now()
    val createRequest = Document.Create(
      id = UUID.randomUUID(),
      userId = userId,
      sha256 = sha256,
      storageKey = storageKey,
      kind = kind,
      filename = filename,
      status = status,
      createdAt = now,
      updatedAt = now
    )
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ProcessDocument,
      payload = net.wiringbits.models.jobs.BackgroundJobPayload.ProcessDocument(createRequest.id),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
    for {
      _ <- repository.documents.create(createRequest, job)
    } yield createRequest
  }
}
