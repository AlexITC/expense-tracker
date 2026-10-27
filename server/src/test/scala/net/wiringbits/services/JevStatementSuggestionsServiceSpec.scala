package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.apis.{JevChoice, JevClient}
import net.wiringbits.repositories.models.{BankStatementRow, Category, Merchant}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

class JevStatementSuggestionsServiceSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.milliseconds)

  private case class Call(state: String, questionName: String, criteria: Map[String, String])

  // Records every call and answers it with `respond`: (state, questionName, criteria) => chosen key.
  private class StubJevClient(respond: (String, String, Map[String, String]) => Future[String]) extends JevClient {
    val calls: mutable.ListBuffer[Call] = mutable.ListBuffer.empty

    override def choose(
        state: String,
        questionName: String,
        instructions: String,
        criteria: Map[String, String]
    ): Future[JevChoice] = {
      calls += Call(state, questionName, criteria)
      respond(state, questionName, criteria).map(choice => JevChoice(choice, BigDecimal("0.9"), Map.empty))
    }

    def categoryCalls: List[Call] = calls.toList.filter(_.questionName == "category")
    def merchantCalls: List[Call] = calls.toList.filter(_.questionName == "merchant")
  }

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

  // Category questions get `categoryChoice`; merchant questions get `merchantChoice`, when given.
  private def stub(categoryChoice: Category, merchantChoice: Option[String] = None) =
    new StubJevClient((_, question, _) =>
      (question, merchantChoice) match {
        case ("category", _) => Future.successful(categoryChoice.id.toString)
        case ("merchant", Some(choice)) => Future.successful(choice)
        case _ => Future.failed(new IllegalStateException(s"unexpected $question question"))
      }
    )

  private def run(
      client: JevClient,
      rows: List[BankStatementRow],
      merchants: List[Merchant],
      categories: List[Category],
      rules: List[ImportRuleMatcher.Rule] = Nil,
      examples: List[(UUID, String)] = Nil,
      history: Map[String, UUID] = Map.empty
  ): Future[AiSuggestions] =
    new JevStatementSuggestionsService(client)
      .resolve(userId, statementId, model, rows, rules, merchants, categories, examples, history)

  private def resolve(
      client: JevClient,
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
    "never send rule-matched or credit rows to JEV, and leave them out of the result" in {
      val ignored = row("BANK FEE MONTHLY")
      val ruled = row("NETFLIX.COM 1234")
      val credit = row("SALARY ACME", BigDecimal(1000))
      val pending = row("CORNER SHOP 42")
      val rules = List(
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, "bank fee", None),
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant, "netflix", Some(UUID.randomUUID()))
      )
      val client = stub(groceries)

      val result = resolve(client, List(ignored, ruled, credit, pending), Nil, categories, rules)

      result.status must be(AiSuggestionsStatus.Done)
      result.model must be(model)
      result.rows must be(List(AiSuggestions.RowSuggestion(pending.id, Some(groceries.id), None)))
      client.calls.map(_.state).toList must be(List("CORNER SHOP 42"))
    }

    "use the history category without calling JEV" in {
      val known = row("Corner-Shop #42")
      val client = stub(transport)

      val result = resolve(client, List(known), Nil, categories, history = Map("cornershop42" -> groceries.id))

      result.rows must be(List(AiSuggestions.RowSuggestion(known.id, Some(groceries.id), None)))
      client.calls must be(empty)
    }

    "ask once per distinct description and give every row sharing it the result, in the original order" in {
      val first = row("CORNER SHOP 42")
      val bus = row("METRO TICKET")
      val second = row("corner-shop #42")
      val third = row("Corner  Shop 42")
      val client = new StubJevClient((state, _, _) =>
        Future.successful(if (state.contains("METRO")) transport.id.toString else groceries.id.toString)
      )

      val result = resolve(client, List(first, bus, second, third), Nil, categories)

      result.rows must be(
        List(
          AiSuggestions.RowSuggestion(first.id, Some(groceries.id), None),
          AiSuggestions.RowSuggestion(bus.id, Some(transport.id), None),
          AiSuggestions.RowSuggestion(second.id, Some(groceries.id), None),
          AiSuggestions.RowSuggestion(third.id, Some(groceries.id), None)
        )
      )
      client.categoryCalls.map(_.state) must be(List("CORNER SHOP 42", "METRO TICKET"))
    }

    "not merge rows whose descriptions normalize to nothing unless their raw text matches" in {
      val a = row("***")
      val b = row("---")
      val client = stub(groceries)

      resolve(client, List(a, b), Nil, categories)

      client.categoryCalls.map(_.state) must be(List("***", "---"))
    }

    "key category criteria by id, describe them with examples, and map the chosen id back" in {
      val plain = category("Super")
      val accented = category("Súper")
      val shop = row("CORNER SHOP")
      val client = stub(accented)

      val result = resolve(client, List(shop), Nil, List(plain, accented), examples = List(accented.id -> "MERCADO"))

      result.rows must be(List(AiSuggestions.RowSuggestion(shop.id, Some(accented.id), None)))
      client.categoryCalls.map(_.criteria) must be(
        List(Map(plain.id.toString -> "Super", accented.id.toString -> "Súper. Examples: MERCADO"))
      )
    }

    "fail when JEV chooses a key that is not an offered category" in {
      val client = new StubJevClient((_, _, _) => Future.successful(UUID.randomUUID().toString))

      val error = run(client, List(row("CORNER SHOP")), Nil, categories).failed.futureValue

      error.getMessage must include("not one of the offered categories")
    }

    "fail without calling JEV when there are more than 255 categories" in {
      val many = (1 to 256).toList.map(i => category(s"Category $i"))
      val client = stub(many.head)

      val error = run(client, List(row("CORNER SHOP")), Nil, many).failed.futureValue

      error.getMessage must include(userId.toString)
      client.calls must be(empty)
    }

    "offer the predicted category's merchants keyed by id plus none" in {
      val shop = merchant("Corner Shop Ltd", Some(groceries.id))
      val market = merchant("Market", Some(groceries.id))
      val metro = merchant("Metro", Some(transport.id))
      val client = stub(groceries, Some(JevClient.NoMerchant))

      resolve(client, List(row("CORNER SHOP")), List(shop, market, metro), categories)

      client.merchantCalls.map(_.criteria.keySet) must be(
        List(Set(shop.id.toString, market.id.toString, JevClient.NoMerchant))
      )
      client.merchantCalls.head.criteria(shop.id.toString) must be("Corner Shop Ltd")
    }

    "set the chosen merchant" in {
      val shop = merchant("Corner Shop Ltd", Some(groceries.id))
      val other = merchant("Other", Some(groceries.id))
      val pending = row("CORNER SHOP")
      val client = stub(groceries, Some(shop.id.toString))

      val result = resolve(client, List(pending), List(shop, other), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(pending.id, Some(groceries.id), Some(shop.id))))
    }

    "give no merchant when JEV answers a key that is not an offered merchant" in {
      val shop = merchant("Corner Shop Ltd", Some(groceries.id))
      val pending = row("CORNER SHOP")
      val client = stub(groceries, Some(UUID.randomUUID().toString))

      val result = resolve(client, List(pending), List(shop), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(pending.id, Some(groceries.id), None)))
    }

    "fail without the merchant call when the category has more than 254 merchants" in {
      val many = (1 to 255).toList.map(i => merchant(s"Merchant $i", Some(groceries.id)))
      val client = stub(groceries, Some(JevClient.NoMerchant))

      val error = run(client, List(row("CORNER SHOP")), many, categories).failed.futureValue

      error.getMessage must include(userId.toString)
      client.calls.map(_.questionName).toList must be(List("category"))
    }

    "ask the merchant once per distinct description and merge it with history hits in row order" in {
      val shop = merchant("Corner Shop Ltd", Some(groceries.id))
      val first = row("CORNER SHOP 42")
      val known = row("METRO TICKET")
      val second = row("corner-shop #42")
      val client = stub(groceries, Some(shop.id.toString))

      val result =
        resolve(
          client,
          List(first, known, second),
          List(shop),
          categories,
          history = Map("metroticket" -> transport.id)
        )

      result.rows must be(
        List(
          AiSuggestions.RowSuggestion(first.id, Some(groceries.id), Some(shop.id)),
          AiSuggestions.RowSuggestion(known.id, Some(transport.id), None),
          AiSuggestions.RowSuggestion(second.id, Some(groceries.id), Some(shop.id))
        )
      )
      client.categoryCalls must have size 1
      client.merchantCalls must have size 1
    }

    "keep the predicted category with no merchant when JEV answers none" in {
      val shop = merchant("Corner Shop Ltd", Some(groceries.id))
      val pending = row("CORNER SHOP")
      val client = stub(groceries, Some(JevClient.NoMerchant))

      val result = resolve(client, List(pending), List(shop), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(pending.id, Some(groceries.id), None)))
    }

    "skip the merchant question when the category has no merchants" in {
      val pending = row("METRO TICKET")
      val client = stub(transport)

      val result = resolve(client, List(pending), List(merchant("Corner Shop Ltd", Some(groceries.id))), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(pending.id, Some(transport.id), None)))
      client.merchantCalls must be(empty)
    }

    "propagate a client failure" in {
      val client = new StubJevClient((_, _, _) => Future.failed(new RuntimeException("JEV is down")))

      val error = run(client, List(row("CORNER SHOP")), Nil, categories).failed.futureValue

      error.getMessage must be("JEV is down")
    }

    "return Done with no rows and no calls when nothing is left to resolve" in {
      val client = stub(groceries)

      val result = resolve(client, List(row("SALARY", BigDecimal(1000))), Nil, categories)

      result must be(AiSuggestions(AiSuggestionsStatus.Done, model, Nil))
      client.calls must be(empty)
    }

    "fail with the userId and no calls when the user has no categories" in {
      val client = stub(groceries)

      val error = run(client, List(row("CORNER SHOP")), Nil, Nil).failed.futureValue

      error.getMessage must include(userId.toString)
      error.getMessage must include("no categories")
      client.calls must be(empty)
    }
  }
}
