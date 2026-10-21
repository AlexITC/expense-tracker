package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.apis.{JevChoice, JevClient}
import net.wiringbits.config.JevConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.JevApiKey
import net.wiringbits.repositories.models.{BankStatement, BankStatementRow, Expense, ExpenseCategorySplit}
import net.wiringbits.services.JevStatementSuggestionsService
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class ResolveStatementSuggestionsActionSpec extends RepositorySpec with RepositoryUtils {

  private val modelId = "test-jev-model"

  private val jevConfig =
    JevConfig(JevApiKey("test"), "https://example.invalid", modelId, JevConfig.Mock, BigDecimal("0.85"))

  // Answers each choose call with the criteria key `respond` picks from (state, questionName, criteria).
  private class StubJevClient(respond: (String, String, Map[String, String]) => String) extends JevClient {
    override def choose(
        state: String,
        questionName: String,
        instructions: String,
        criteria: Map[String, String]
    ): Future[JevChoice] =
      Future.successful(JevChoice(respond(state, questionName, criteria), BigDecimal(1), Map.empty))
  }

  private class FailingJevClient(message: String) extends JevClient {
    override def choose(
        state: String,
        questionName: String,
        instructions: String,
        criteria: Map[String, String]
    ): Future[JevChoice] = Future.failed(new RuntimeException(message))
  }

  private def action(client: JevClient)(implicit repositories: RepositoryComponents) =
    new ResolveStatementSuggestionsAction(
      repositories.bankStatements,
      repositories.importRules,
      repositories.merchants,
      repositories.categories,
      new JevStatementSuggestionsService(client),
      jevConfig
    )(using executionContext, Clock.systemUTC())

  /** The key of the category criterion naming `name` (criteria may also list example descriptions). */
  private def categoryKey(criteria: Map[String, String], name: String): String =
    criteria.collectFirst { case (key, text) if text.startsWith(name) => key }.getOrElse(fail(s"No $name in $criteria"))

  private def buildRow(stmtId: UUID, index: Int, description: String, amount: BigDecimal): BankStatementRow.Create = {
    val now = Instant.now()
    BankStatementRow.Create(
      id = UUID.randomUUID(),
      bankStatementId = stmtId,
      index = index,
      date = LocalDate.of(2026, 1, 15),
      amount = amount,
      description = description,
      manuallyEdited = false,
      createdAt = now,
      updatedAt = now,
      txnType = "purchase"
    )
  }

  private case class Seeded(
      userId: UUID,
      stmtId: UUID,
      groceriesId: UUID,
      transportId: UUID,
      superMartId: UUID,
      uberId: UUID,
      groceryRow: BankStatementRow.Create,
      taxiRow: BankStatementRow.Create,
      ruleRow: BankStatementRow.Create,
      creditRow: BankStatementRow.Create
  )

  private def seed()(implicit repositories: RepositoryComponents): Seeded = {
    val user = createUser().futureValue
    val groceries = createCategory(user.id, "Groceries").futureValue
    val transport = createCategory(user.id, "Transport").futureValue
    val superMart = createMerchant(user.id, "SuperMart", categoryId = Some(groceries.id)).futureValue
    val uber = createMerchant(user.id, "Uber", categoryId = Some(transport.id)).futureValue
    createImportRule(user.id, pattern = "netflix", action = ImportRuleAction.Ignore).futureValue

    val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
    val now = Instant.now()
    val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
    val groceryRow = buildRow(stmt.id, 0, "SUPERMART 123", BigDecimal("-45.10"))
    val taxiRow = buildRow(stmt.id, 1, "UBER TRIP", BigDecimal("-12.00"))
    val ruleRow = buildRow(stmt.id, 2, "NETFLIX.COM", BigDecimal("-15.99"))
    val creditRow = buildRow(stmt.id, 3, "PAYROLL", BigDecimal("1000.00"))
    createStatement(stmt, sampleStatementMetadata, List(groceryRow, taxiRow, ruleRow, creditRow)).futureValue

    Seeded(user.id, stmt.id, groceries.id, transport.id, superMart.id, uber.id, groceryRow, taxiRow, ruleRow, creditRow)
  }

  private def storedSuggestions(s: Seeded)(implicit repositories: RepositoryComponents): AiSuggestions =
    repositories.bankStatements.find(s.stmtId, s.userId).futureValue.value.aiSuggestions.value

  "apply" should {
    "store Done suggestions for the debit rows no rule handles" in withRepositories() { implicit repositories =>
      val s = seed()
      val client = new StubJevClient((state, question, criteria) =>
        (state, question) match {
          case ("SUPERMART 123", "category") => categoryKey(criteria, "Groceries")
          case ("SUPERMART 123", "merchant") => s.superMartId.toString
          case ("UBER TRIP", "category") => categoryKey(criteria, "Transport")
          case ("UBER TRIP", "merchant") => s.uberId.toString
          case _ => fail(s"Unexpected question $question for '$state'")
        }
      )

      action(client).apply(s.stmtId).futureValue

      val stored = storedSuggestions(s)
      stored.status must be(AiSuggestionsStatus.Done)
      stored.model must be(modelId)
      stored.rows.toSet must be(
        Set(
          AiSuggestions.RowSuggestion(s.groceryRow.id, Some(s.groceriesId), Some(s.superMartId)),
          AiSuggestions.RowSuggestion(s.taxiRow.id, Some(s.transportId), Some(s.uberId))
        )
      )
      stored.rows.map(_.rowId) must not contain s.ruleRow.id
      stored.rows.map(_.rowId) must not contain s.creditRow.id
    }

    "use the category of an earlier row with the same description without asking the model" in withRepositories() {
      implicit repositories =>
        val s = seed()
        val doc = createDocument(s.userId, sha256 = "earlier", kind = DocumentKind.BankStatementPdf).futureValue
        val now = Instant.now()
        val earlierStmt = BankStatement.Create(UUID.randomUUID(), doc.id, s.userId, now, now)
        val earlierRow = buildRow(earlierStmt.id, 0, "SUPERMART 123", BigDecimal("-30.00"))
        createStatement(earlierStmt, sampleStatementMetadata, List(earlierRow)).futureValue
        val expense = Expense.Create(
          id = UUID.randomUUID(),
          userId = s.userId,
          amount = BigDecimal("30.00"),
          date = LocalDate.of(2025, 12, 15),
          time = None,
          merchantId = None,
          notes = None,
          createdAt = now,
          updatedAt = now
        )
        val split = ExpenseCategorySplit.Create(UUID.randomUUID(), s.userId, expense.id, s.groceriesId, expense.amount)
        repositories.expenses.create(expense, List(split)).futureValue
        repositories.bankStatements.setRowExpenseIdForUser(earlierRow.id, s.userId, expense.id, now).futureValue

        val client = new StubJevClient((state, question, criteria) =>
          (state, question) match {
            case ("SUPERMART 123", _) => fail(s"History row sent to JEV: $question for '$state'")
            case ("UBER TRIP", "category") => categoryKey(criteria, "Transport")
            case ("UBER TRIP", "merchant") => s.uberId.toString
            case _ => fail(s"Unexpected question $question for '$state'")
          }
        )

        action(client).apply(s.stmtId).futureValue

        storedSuggestions(s).rows.toSet must be(
          Set(
            AiSuggestions.RowSuggestion(s.groceryRow.id, Some(s.groceriesId), None),
            AiSuggestions.RowSuggestion(s.taxiRow.id, Some(s.transportId), Some(s.uberId))
          )
        )
    }

    "store Failed suggestions and fail when the JEV client fails" in withRepositories() { implicit repositories =>
      val s = seed()
      val client = new FailingJevClient("JEV is down")

      val ex = intercept[Exception](action(client).apply(s.stmtId).futureValue)
      ex.getMessage must include("JEV is down")

      storedSuggestions(s) must be(AiSuggestions(AiSuggestionsStatus.Failed, modelId, Nil))
    }

    "store Failed suggestions and fail when the user has rows but no categories" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
        val now = Instant.now()
        val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
        createStatement(
          stmt,
          sampleStatementMetadata,
          List(buildRow(stmt.id, 0, "CORNER SHOP", BigDecimal("-9.99")))
        ).futureValue
        val client = new StubJevClient((_, _, _) => fail("JEV must not be called without categories"))

        val ex = intercept[Exception](action(client).apply(stmt.id).futureValue)
        ex.getMessage must include("no categories")

        repositories.bankStatements.find(stmt.id, user.id).futureValue.value.aiSuggestions.value must be(
          AiSuggestions(AiSuggestionsStatus.Failed, modelId, Nil)
        )
    }

    "fail when the statement does not exist" in withRepositories() { implicit repositories =>
      val client = new StubJevClient((_, _, _) => fail("JEV must not be called for a missing statement"))
      val id = UUID.randomUUID()

      val ex = intercept[Exception](action(client).apply(id).futureValue)
      ex.getMessage must include(s"Bank statement $id not found")
    }
  }
}
