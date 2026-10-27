package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.apis.BedrockClient
import net.wiringbits.repositories.models.{BankStatementRow, Category, Merchant}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.{Locale, UUID}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

class StatementSuggestionsServiceSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.milliseconds)

  // Records every prompt and answers it with `respond`; a prompt `respond` does not handle fails the test.
  private class StubBedrockClient(respond: PartialFunction[String, String]) extends BedrockClient {
    val prompts: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override def extractBankStatement(statementText: String, feedback: Option[String]): Future[String] =
      Future.failed(new UnsupportedOperationException("not used"))

    override def suggest(prompt: String): Future[String] = {
      prompts += prompt
      respond
        .lift(prompt)
        .fold(Future.failed[String](new IllegalStateException(s"unexpected suggest call: $prompt")))(
          Future.successful
        )
    }

    def categoryPrompts: List[String] = prompts.toList.filter(isCategoryPrompt)
    def merchantPrompts: List[String] = prompts.toList.filter(isMerchantPrompt)
  }

  private def isCategoryPrompt(prompt: String) = prompt.contains("<category name>")
  private def isMerchantPrompt(prompt: String) = prompt.contains("<merchant name>")

  private val now = Instant.now()
  private val userId = UUID.randomUUID()
  private val statementId = UUID.randomUUID()
  private val model = "test-model"

  private def row(description: String, amount: BigDecimal = BigDecimal(-10)) =
    BankStatementRow(
      id = UUID.randomUUID(),
      bankStatementId = statementId,
      index = 0,
      date = LocalDate.of(2026, 1, 15),
      amount = amount,
      description = description,
      manuallyEdited = false,
      confirmedAt = None,
      expenseId = None,
      refundId = None,
      ignoredAt = None,
      txnType = "purchase",
      createdAt = now,
      updatedAt = now
    )

  private def category(name: String) = Category(UUID.randomUUID(), userId, name, now, now)

  private def merchant(name: String, categoryId: Option[UUID]) =
    Merchant(UUID.randomUUID(), userId, name, None, categoryId, None, reviewed = true, now, now)

  private def answer(name: String): String =
    s"""{
       |  "answer": "$name"
       |}""".stripMargin

  // Category prompts get `categoryAnswer`; merchant prompts get `merchantAnswer`, when given.
  private def stub(categoryAnswer: String, merchantAnswer: Option[String] = None) =
    new StubBedrockClient({
      case p if isCategoryPrompt(p) => answer(categoryAnswer)
      case p if isMerchantPrompt(p) && merchantAnswer.isDefined => answer(merchantAnswer.get)
    })

  private def run(
      client: BedrockClient,
      rows: List[BankStatementRow],
      merchants: List[Merchant],
      categories: List[Category],
      rules: List[ImportRuleMatcher.Rule] = Nil,
      examples: List[(UUID, String)] = Nil,
      history: Map[String, UUID] = Map.empty
  ): Future[AiSuggestions] =
    new StatementSuggestionsService(client)
      .resolve(userId, statementId, model, rows, rules, merchants, categories, examples, history)

  private def resolve(
      client: BedrockClient,
      rows: List[BankStatementRow],
      merchants: List[Merchant],
      categories: List[Category],
      rules: List[ImportRuleMatcher.Rule] = Nil,
      examples: List[(UUID, String)] = Nil,
      history: Map[String, UUID] = Map.empty
  ): AiSuggestions =
    run(client, rows, merchants, categories, rules, examples, history).futureValue

  private val groceries = category("Groceries")
  private val transport = category("Transport")
  private val categories = List(groceries, transport)

  "resolve" should {
    "never send rule-matched or credit rows to Bedrock, and leave them out of the result" in {
      val ignored = row("BANK FEE MONTHLY")
      val ruled = row("NETFLIX.COM 1234")
      val ruledWithoutMerchant = row("SPOTIFY P1234")
      val credit = row("SALARY ACME", BigDecimal(1000))
      val pending = row("CORNER SHOP 42")
      val rules = List(
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, "bank fee"),
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant(UUID.randomUUID()), "netflix"),
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant(UUID.randomUUID()), "spotify")
      )
      val client = stub("Groceries")

      val result = resolve(client, List(ignored, ruled, ruledWithoutMerchant, credit, pending), Nil, categories, rules)

      result.status must be(AiSuggestionsStatus.Done)
      result.model must be(model)
      result.rows must be(List(AiSuggestions.RowSuggestion(pending.id, Some(groceries.id), None)))
      client.prompts must have size 1
      client.prompts.head must include("CORNER SHOP 42")
      List("BANK FEE", "NETFLIX", "SPOTIFY", "SALARY").foreach(d => client.prompts.head must not include d)
    }

    "send one category prompt per row" in {
      val bus = row("METRO TICKET")
      val shop = row("CORNER SHOP")
      val client = new StubBedrockClient({
        case p if p.contains("METRO TICKET") => answer("Transport")
        case p if p.contains("CORNER SHOP") => answer("Groceries")
      })

      val result = resolve(client, List(bus, shop), Nil, categories)

      result.rows must be(
        List(
          AiSuggestions.RowSuggestion(bus.id, Some(transport.id), None),
          AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None)
        )
      )
      client.categoryPrompts must have size 2
      client.categoryPrompts(0) must include("METRO TICKET")
      client.categoryPrompts(0) must not include "CORNER SHOP"
      client.categoryPrompts(1) must include("CORNER SHOP")
      client.categoryPrompts(1) must not include "METRO TICKET"
    }

    "list category names with their examples, no ids and no way to answer none" in {
      val bus = row("METRO TICKET")
      val client = stub("Transport")

      resolve(client, List(bus), Nil, categories, examples = List(transport.id -> "CITY BUS"))

      val prompt = client.prompts.head
      prompt must include("Groceries")
      prompt must include("Transport")
      prompt must include("CITY BUS")
      List(groceries.id, transport.id, bus.id).foreach(id => prompt must not include id.toString)
      List("none", "null").foreach(word => prompt.toLowerCase(Locale.ROOT) must not include word)
    }

    "map the returned category name to its id ignoring case and accents" in {
      val shop = row("CORNER SHOP")
      resolve(stub("Gróceries"), List(shop), Nil, categories).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None))
      )
    }

    "leave the row without a category when the name is unknown" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop Ltd", Some(groceries.id))
      val client = stub("Restaurants")

      resolve(client, List(shop), List(cornerShop), categories).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, None, None))
      )
      client.merchantPrompts must be(empty)
    }

    "leave the row without a category when the name matches several categories" in {
      val shop = row("CORNER SHOP")
      val plain = category("Super")
      val accented = category("Súper")

      resolve(stub("super"), List(shop), Nil, List(plain, accented)).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, None, None))
      )
    }

    "offer only the predicted category's merchant names in the merchant prompt" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop Ltd", Some(groceries.id))
      val uber = merchant("Uber", Some(transport.id))
      val uncategorized = merchant("Mystery", None)
      val client = stub("Groceries", Some("none"))

      resolve(client, List(shop), List(cornerShop, uber, uncategorized), categories)

      client.prompts must have size 2
      val prompt = client.merchantPrompts.head
      prompt must include("Corner Shop")
      prompt must include("CORNER SHOP")
      prompt must include("\"Groceries\"")
      List("Uber", "Mystery").foreach(name => prompt must not include name)
      List(cornerShop.id, groceries.id, shop.id).foreach(id => prompt must not include id.toString)
    }

    "set the chosen merchant" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop Ltd", Some(groceries.id))

      resolve(stub("Groceries", Some("corner shop ltd")), List(shop), List(cornerShop), categories).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), Some(cornerShop.id)))
      )
    }

    "keep the predicted category when the merchant answer is none" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop Ltd", Some(groceries.id))

      resolve(stub("Groceries", Some("None")), List(shop), List(cornerShop), categories).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None))
      )
    }

    "leave the merchant out when the name is not one of the offered merchants" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop Ltd", Some(groceries.id))
      val uber = merchant("Uber", Some(transport.id))

      resolve(stub("Groceries", Some("Uber")), List(shop), List(cornerShop, uber), categories).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None))
      )
    }

    "leave the merchant out when the name matches several merchants" in {
      val shop = row("CORNER SHOP")
      val first = merchant("Corner Shop Ltd", Some(groceries.id))
      val second = merchant("Corner Shop Ltd", Some(groceries.id))

      resolve(stub("Groceries", Some("Corner Shop Ltd")), List(shop), List(first, second), categories).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None))
      )
    }

    "skip the merchant step for a category without merchants" in {
      val bus = row("METRO TICKET")
      val client = stub("Transport")

      val result = resolve(client, List(bus), List(merchant("Corner Shop Ltd", Some(groceries.id))), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(bus.id, Some(transport.id), None)))
      client.prompts must have size 1
    }

    "fail with the raw text when the model answer is not valid JSON or has no string answer" in {
      val notAString =
        """{
          |  "answer": []
          |}""".stripMargin
      val oldShape =
        """{
          |  "rows": []
          |}""".stripMargin
      List("Sorry, I cannot help with that", notAString, oldShape).foreach { raw =>
        val client = new StubBedrockClient({ case _ => raw })
        val error = run(client, List(row("CORNER SHOP")), Nil, categories).failed.futureValue
        error mustBe a[RuntimeException]
        error.getMessage must include(raw)
      }
    }

    "fail with the raw text when the merchant answer is not valid JSON" in {
      val shop = row("CORNER SHOP")
      val client = new StubBedrockClient({
        case p if isCategoryPrompt(p) => answer("Groceries")
        case p if isMerchantPrompt(p) => "not json"
      })

      val error =
        run(client, List(shop), List(merchant("Corner Shop Ltd", Some(groceries.id))), categories).failed.futureValue

      error mustBe a[RuntimeException]
      error.getMessage must include("not json")
    }

    "make no Bedrock call and return Done with no rows when there is nothing to resolve" in {
      val client = new StubBedrockClient(PartialFunction.empty)

      resolve(client, List(row("SALARY", BigDecimal(1000))), Nil, categories) must be(
        AiSuggestions(AiSuggestionsStatus.Done, model, Nil)
      )
      client.prompts must be(empty)
    }

    "use the history category without calling Bedrock" in {
      val shop = row("CORNER SHOP 42")
      val client = new StubBedrockClient(PartialFunction.empty)
      val merchants = List(merchant("Corner Shop Ltd", Some(groceries.id)))

      resolve(client, List(shop), merchants, categories, history = Map("cornershop42" -> groceries.id)).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None))
      )
      client.prompts must be(empty)
    }

    "hit the history for a description differing only in case or punctuation" in {
      val shop = row("corner-shop #42")
      val client = new StubBedrockClient(PartialFunction.empty)

      resolve(client, List(shop), Nil, categories, history = Map("cornershop42" -> groceries.id)).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None))
      )
      client.prompts must be(empty)
    }

    "send the row to the model when its history category is not among the categories" in {
      val shop = row("CORNER SHOP 42")
      val client = stub("Transport")

      resolve(client, List(shop), Nil, categories, history = Map("cornershop42" -> UUID.randomUUID())).rows must be(
        List(AiSuggestions.RowSuggestion(shop.id, Some(transport.id), None))
      )
      client.categoryPrompts must have size 1
    }

    "call the model only for the rows without history, keeping the row order" in {
      val bus = row("METRO TICKET")
      val shop = row("CORNER SHOP 42")
      val client = stub("Transport")

      val result = resolve(client, List(shop, bus), Nil, categories, history = Map("cornershop42" -> groceries.id))

      result.rows must be(
        List(
          AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None),
          AiSuggestions.RowSuggestion(bus.id, Some(transport.id), None)
        )
      )
      client.categoryPrompts must have size 1
      client.categoryPrompts.head must include("METRO TICKET")
      client.categoryPrompts.head must not include "CORNER SHOP"
    }

    "fail without calling Bedrock when there are rows but no categories" in {
      val client = new StubBedrockClient(PartialFunction.empty)

      val error = run(client, List(row("CORNER SHOP")), Nil, Nil).failed.futureValue

      error mustBe a[RuntimeException]
      error.getMessage must include(userId.toString)
      client.prompts must be(empty)
    }
  }

  "selectRowsToResolve" should {
    "skip by rule a debit row whose description contains a merchant name" in {
      val named = row("OXXO STORE 123")
      val pending = row("CORNER SHOP 42")

      StatementSuggestionsService.selectRowsToResolve(
        List(named, pending),
        Nil,
        List(merchant("Oxxo", None))
      ) must be(StatementSuggestionsService.RowSelection(List(pending), creditsSkipped = 0, ruleSkipped = 1))
    }

    "skip by rule a debit row matched only by a set_category rule" in {
      val categorized = row("CAFETERIA CENTRAL")
      val pending = row("CORNER SHOP 42")
      val rule = ImportRuleMatcher.Rule(
        UUID.randomUUID(),
        ImportRuleAction.SetCategory(UUID.randomUUID()),
        "cafeteria"
      )

      StatementSuggestionsService.selectRowsToResolve(
        List(categorized, pending),
        List(rule),
        Nil
      ) must be(StatementSuggestionsService.RowSelection(List(pending), creditsSkipped = 0, ruleSkipped = 1))
    }
  }

  "matchByName" should {
    val names = List("Groceries", "Café Central")

    "match ignoring case, accents and spacing" in {
      StatementSuggestionsService.matchByName(" cafe   CENTRAL ", names)(identity) must be(Some("Café Central"))
      StatementSuggestionsService.matchByName("GROCERIES", names)(identity) must be(Some("Groceries"))
    }

    "return None when no candidate matches" in {
      StatementSuggestionsService.matchByName("Transport", names)(identity) must be(None)
    }

    "return None when several candidates differ only in spacing" in {
      StatementSuggestionsService.matchByName("corner shop", List("Corner Shop", "Corner  Shop"))(identity) must be(
        None
      )
    }
  }

  "finalSuggestion" should {
    "use the chosen merchant's category over the predicted one" in {
      val rowId = UUID.randomUUID()
      val uber = merchant("Uber", Some(transport.id))

      StatementSuggestionsService.finalSuggestion(rowId, Some(groceries.id), Some(uber.id), List(uber)) must be(
        AiSuggestions.RowSuggestion(rowId, Some(transport.id), Some(uber.id))
      )
    }

    "keep the predicted category when the chosen merchant has none" in {
      val rowId = UUID.randomUUID()
      val mystery = merchant("Mystery", None)

      StatementSuggestionsService.finalSuggestion(rowId, Some(groceries.id), Some(mystery.id), List(mystery)) must be(
        AiSuggestions.RowSuggestion(rowId, Some(groceries.id), Some(mystery.id))
      )
    }
  }
}
