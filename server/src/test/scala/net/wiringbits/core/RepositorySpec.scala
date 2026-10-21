package net.wiringbits.core

import net.wiringbits.config.UserTokensConfig
import net.wiringbits.repositories.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.wordspec.AnyWordSpec
import utils.Executors

import java.time.Clock
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

trait RepositorySpec extends AnyWordSpec with PostgresSpec {
  implicit val patienceConfig: PatienceConfig = PatienceConfig(30.seconds, 1.second)
  implicit val executionContext: ExecutionContext = Executors.globalEC

  def withRepositories[T](clock: Clock = Clock.systemUTC)(runTest: RepositoryComponents => T): T = withDatabase { db =>
    val users = new UsersRepository(db, UserTokensConfig(1.hour, 1.hour, "secret"))(using Executors.databaseEC, clock)
    val userTokens = new UserTokensRepository(db)(using Executors.databaseEC)
    val userLogs = new UserLogsRepository(db)(using Executors.databaseEC)
    val backgroundJobs = new BackgroundJobsRepository(db)(using Executors.databaseEC, clock)
    val categories = new CategoriesRepository(db)(using Executors.databaseEC, clock)
    val merchants = new MerchantsRepository(db)(using Executors.databaseEC, clock)
    val expenses = new ExpensesRepository(db)(using Executors.databaseEC, clock)
    val documents = new DocumentsRepository(db)(using Executors.databaseEC)
    val textractCache = new TextractCacheRepository(db)(using Executors.databaseEC)
    val bankStatements = new BankStatementRepository(db)(using Executors.databaseEC)
    val importRules = new ImportRuleRepository(db)(using Executors.databaseEC, clock)
    val receipts = new ReceiptRepository(db)(using Executors.databaseEC, clock)
    val refunds = new RefundsRepository(db)(using Executors.databaseEC, clock)
    val savings = new SavingsRepository(db)(using Executors.databaseEC, clock)
    val textractJobs = new TextractJobRepository(db)(using Executors.databaseEC, clock)
    val telegram = new TelegramRepository(db)(using Executors.databaseEC)
    val telegramReceipt = new TelegramReceiptRepository(db)(using Executors.databaseEC)
    val mcp = new McpRepository(db)(using Executors.databaseEC)
    val components =
      RepositoryComponents(
        db,
        users,
        userTokens,
        userLogs,
        backgroundJobs,
        categories,
        merchants,
        expenses,
        documents,
        textractCache,
        bankStatements,
        importRules,
        receipts,
        refunds,
        savings,
        textractJobs,
        telegram,
        telegramReceipt,
        mcp
      )
    runTest(components)
  }

  /** background_jobs is a global queue that isn't scoped by user, and claims pick any pending job of a type, so claim
    * semantics can only be tested against an empty queue. Only the background job specs should use this.
    */
  def withEmptyJobQueue[T](runTest: RepositoryComponents => T): T = withRepositories() { repositories =>
    repositories.database.withConnection { implicit conn =>
      anorm.SQL("DELETE FROM background_jobs").executeUpdate()
    }
    runTest(repositories)
  }
}
