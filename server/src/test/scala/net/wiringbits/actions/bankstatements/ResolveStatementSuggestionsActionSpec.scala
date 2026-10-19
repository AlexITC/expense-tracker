package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.apis.BedrockClient
import net.wiringbits.config.BedrockConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import net.wiringbits.repositories.models.{BankStatement, BankStatementRow}
import net.wiringbits.services.StatementSuggestionsService
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import software.amazon.awssdk.regions.Region
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class ResolveStatementSuggestionsActionSpec extends RepositorySpec with RepositoryUtils {

  private val modelId = "test-suggestions-model"

  private val bedrockConfig = BedrockConfig(
    AWSAccessKeyId("test"),
    AWSSecretAccessKey("test"),
    Region.EU_WEST_1,
    modelId = "test-model",
    statementModelId = "test-statement-model",
    statementMaxTokens = 1000,
    suggestionsModelId = modelId,
    mode = BedrockConfig.Mock
  )

  // Answers each suggest call with whatever `respond` computes from the prompt.
  private class StubBedrockClient(respond: String => String) extends BedrockClient {
    override def extractBankStatement(statementText: String, feedback: Option[String]): Future[String] =
      Future.failed(new UnsupportedOperationException("not used"))

    override def suggest(prompt: String): Future[String] = Future.successful(respond(prompt))
  }

  private def action(client: BedrockClient)(implicit repositories: RepositoryComponents) =
    new ResolveStatementSuggestionsAction(
      repositories.bankStatements,
      repositories.importRules,
      repositories.merchants,
      repositories.categories,
      new StatementSuggestionsService(client),
      bedrockConfig
    )(using executionContext, Clock.systemUTC())

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

  private def answer(name: String): String =
    s"""{
       |  "answer": "$name"
       |}""".stripMargin

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
      // Merchant prompts quote their category; category prompts are told apart by the row description.
      val client = new StubBedrockClient(prompt =>
        if (prompt.contains("\"Groceries\"")) answer("SuperMart")
        else if (prompt.contains("\"Transport\"")) answer("Uber")
        else if (prompt.contains("SUPERMART 123")) answer("Groceries")
        else if (prompt.contains("UBER TRIP")) answer("Transport")
        else fail(s"Unexpected prompt: $prompt")
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

    "store Failed suggestions and fail when the model output is unparseable" in withRepositories() {
      implicit repositories =>
        val s = seed()
        val client = new StubBedrockClient(_ => "not json at all")

        val ex = intercept[Exception](action(client).apply(s.stmtId).futureValue)
        ex.getMessage must include("not json at all")

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
        val client = new StubBedrockClient(_ => fail("Bedrock must not be called without categories"))

        val ex = intercept[Exception](action(client).apply(stmt.id).futureValue)
        ex.getMessage must include("no categories")

        repositories.bankStatements.find(stmt.id, user.id).futureValue.value.aiSuggestions.value must be(
          AiSuggestions(AiSuggestionsStatus.Failed, modelId, Nil)
        )
    }

    "fail when the statement does not exist" in withRepositories() { implicit repositories =>
      val client = new StubBedrockClient(_ => fail("Bedrock must not be called for a missing statement"))
      val id = UUID.randomUUID()

      val ex = intercept[Exception](action(client).apply(id).futureValue)
      ex.getMessage must include(s"Bank statement $id not found")
    }
  }
}
