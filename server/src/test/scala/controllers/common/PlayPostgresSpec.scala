package controllers.common

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.ApiClient
import net.wiringbits.core.SharedPostgres
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.BankStatementRepository
import net.wiringbits.repositories.daos.DocumentDAO
import net.wiringbits.repositories.models.{BackgroundJobData, BankStatement, BankStatementRow, Document}
import org.scalatest.TestData
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.play.guice.GuiceOneServerPerTest
import play.api.db.Database
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Configuration, Environment, Mode}
import sttp.client3.HttpClientFutureBackend

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

trait PlayPostgresSpec extends PlayAPISpec with GuiceOneServerPerTest {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(30.seconds, 1.second)

  /** Loads configuration pointing the default database to the given container.
    *
    * This allows to not write a custom application.conf for testing. The shared container is already evolved, so Play's
    * startup evolutions find nothing to apply.
    */
  private def loadConfigForContainer(env: Environment, container: PostgreSQLContainer): Configuration = {
    val map = Map(
      "db.default.username" -> container.username,
      "db.default.password" -> container.password,
      "db.default.url" -> container.jdbcUrl
    )

    Configuration.from(map).withFallback(Configuration.load(env))
  }

  def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    GuiceApplicationBuilder(loadConfiguration = env => loadConfigForContainer(env, container))
      .in(Mode.Test)

  override def newAppForTest(testData: TestData): Application =
    guiceApplicationBuilder(SharedPostgres.container).build()

  def withApiClient[A](runTest: ApiClient => A): A = {
    implicit val sttpBackend: sttp.client3.SttpBackend[concurrent.Future, ?] = HttpClientFutureBackend()

    val config = ApiClient.Config(s"http://localhost:$port")
    val client = new ApiClient(config)
    runTest(client)
  }

  /** Create a confirmed bank statement with rows directly (no upload / Textract), for tests. */
  def createBankStatementWithRows(userId: UUID, rows: UUID => List[BankStatementRow.Create]): UUID = {
    val repo = app.injector.instanceOf[BankStatementRepository]
    val now = Instant.now()
    val stmtId = UuidCreator.getTimeOrderedEpoch()
    val docId = UuidCreator.getTimeOrderedEpoch()
    val document = Document.Create(
      id = docId,
      userId = userId,
      sha256 = "sha-" + UUID.randomUUID().toString,
      storageKey = "key",
      kind = DocumentKind.BankStatementPdf,
      filename = "stmt.pdf",
      createdAt = now,
      updatedAt = now
    )
    val stmt = BankStatement.Create(stmtId, docId, userId, now, now)
    val metadata = BankStatement.Metadata(
      bankName = "Test Bank",
      productType = "credit",
      currency = "USD",
      accountLast4 = "0000",
      periodStart = LocalDate.of(2026, 1, 1),
      periodEnd = LocalDate.of(2026, 1, 31)
    )
    // Scheduled far in the future so the live executor never resolves suggestions while a test inspects the statement.
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ResolveStatementSuggestions,
      payload = BackgroundJobPayload.ResolveStatementSuggestions(stmtId),
      status = BackgroundJobStatus.Pending,
      executeAt = now.plus(Duration.ofDays(365)),
      createdAt = now,
      updatedAt = now
    )
    app.injector.instanceOf[Database].withConnection { implicit conn => DocumentDAO.create(document) }
    repo.createConfirmedStatement(stmt, metadata, rows(stmtId), extractionInfo = None, job).futureValue
    stmtId
  }

  def createBankStatement(userId: UUID): UUID = createBankStatementWithRows(userId, _ => Nil)

  implicit class RichFutureExt[T](val future: Future[T]) {
    def expectError: String = {
      future
        .map(_ => "Success when failure expected")
        .recover { case NonFatal(ex) =>
          ex.getMessage
        }
        .futureValue
    }
  }
}
