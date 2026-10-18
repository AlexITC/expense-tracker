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
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

class StatementSuggestionsServiceSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.milliseconds)

  // Records every prompt and answers with the scripted responses, in order.
  private class StubBedrockClient(responses: String*) extends BedrockClient {
    val prompts: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    private val remaining = mutable.Queue(responses*)

    override def extractBankStatement(statementText: String, feedback: Option[String]): Future[String] =
      Future.failed(new UnsupportedOperationException("not used"))

    override def suggest(prompt: String): Future[String] = {
      prompts += prompt
      if (remaining.isEmpty) Future.failed(new IllegalStateException("unexpected suggest call"))
      else Future.successful(remaining.dequeue())
    }
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

  private def answer(field: String, pairs: (UUID, Option[UUID])*): String =
    pairs
      .map { case (rowId, id) => s"""{"rowId":"$rowId","$field":${id.fold("null")(i => s"\"$i\"")}}""" }
      .mkString("""{"rows":[""", ",", "]}")

  private def resolve(
      client: BedrockClient,
      rows: List[BankStatementRow],
      merchants: List[Merchant],
      categories: List[Category],
      rules: List[ImportRuleMatcher.Rule] = Nil,
      examples: List[(UUID, String)] = Nil
  ): AiSuggestions =
    new StatementSuggestionsService(client)
      .resolve(statementId, model, rows, rules, merchants, categories, examples)
      .futureValue

  private val groceries = category("Groceries")
  private val transport = category("Transport")
  private val categories = List(groceries, transport)

  "resolve" should {
    "never send rule-matched or credit rows to Bedrock, and leave them out of the result" in {
      val ignored = row("BANK FEE MONTHLY")
      val ruled = row("NETFLIX.COM 1234")
      val credit = row("SALARY ACME", BigDecimal(1000))
      val pending = row("CORNER SHOP 42")
      val rules = List(
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, "bank fee", None),
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant, "netflix", Some(UUID.randomUUID()))
      )
      val client = new StubBedrockClient(answer("categoryId", pending.id -> None))

      val result = resolve(client, List(ignored, ruled, credit, pending), Nil, categories, rules)

      result.status must be(AiSuggestionsStatus.Done)
      result.model must be(model)
      result.rows must be(List(AiSuggestions.RowSuggestion(pending.id, None, None)))
      client.prompts must have size 1
      client.prompts.head must include("CORNER SHOP 42")
      List("BANK FEE", "NETFLIX", "SALARY").foreach(d => client.prompts.head must not include d)
    }

    "map the category step's answer to each row" in {
      val bus = row("METRO TICKET")
      val shop = row("CORNER SHOP")
      val client = new StubBedrockClient(answer("categoryId", bus.id -> Some(transport.id), shop.id -> None))

      val result = resolve(client, List(bus, shop), Nil, categories, examples = List(transport.id -> "CITY BUS"))

      result.rows must be(
        List(
          AiSuggestions.RowSuggestion(bus.id, Some(transport.id), None),
          AiSuggestions.RowSuggestion(shop.id, None, None)
        )
      )
      client.prompts.head must include(transport.id.toString)
      client.prompts.head must include("CITY BUS")
    }

    "offer only the predicted category's merchants in the merchant prompt" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop", Some(groceries.id))
      val uber = merchant("Uber", Some(transport.id))
      val uncategorized = merchant("Mystery", None)
      val client = new StubBedrockClient(
        answer("categoryId", shop.id -> Some(groceries.id)),
        answer("merchantId", shop.id -> None)
      )

      resolve(client, List(shop), List(cornerShop, uber, uncategorized), categories)

      client.prompts must have size 2
      val merchantPrompt = client.prompts(1)
      merchantPrompt must include(cornerShop.id.toString)
      merchantPrompt must not include uber.id.toString
      merchantPrompt must not include uncategorized.id.toString
    }

    "set the chosen merchant and keep its category" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop", Some(groceries.id))
      val client = new StubBedrockClient(
        answer("categoryId", shop.id -> Some(groceries.id)),
        answer("merchantId", shop.id -> Some(cornerShop.id))
      )

      val result = resolve(client, List(shop), List(cornerShop), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), Some(cornerShop.id))))
    }

    "resolve merchants per predicted category and merge every group" in {
      val shop = row("CORNER SHOP")
      val ride = row("UBER TRIP")
      val cornerShop = merchant("Corner Shop", Some(groceries.id))
      val uber = merchant("Uber", Some(transport.id))
      val client = new StubBedrockClient(
        answer("categoryId", shop.id -> Some(groceries.id), ride.id -> Some(transport.id)),
        answer("merchantId", shop.id -> Some(cornerShop.id)),
        answer("merchantId", ride.id -> Some(uber.id))
      )

      val result = resolve(client, List(shop, ride), List(cornerShop, uber), categories)

      result.rows must be(
        List(
          AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), Some(cornerShop.id)),
          AiSuggestions.RowSuggestion(ride.id, Some(transport.id), Some(uber.id))
        )
      )
      client.prompts must have size 3
      val groceriesPrompt = client.prompts(1)
      groceriesPrompt must include(cornerShop.id.toString)
      groceriesPrompt must include(shop.id.toString)
      groceriesPrompt must not include uber.id.toString
      groceriesPrompt must not include ride.id.toString
      val transportPrompt = client.prompts(2)
      transportPrompt must include(uber.id.toString)
      transportPrompt must include(ride.id.toString)
      transportPrompt must not include cornerShop.id.toString
      transportPrompt must not include shop.id.toString
    }

    "fail when the merchant answer is not valid JSON" in {
      val shop = row("CORNER SHOP")
      val client = new StubBedrockClient(answer("categoryId", shop.id -> Some(groceries.id)), "not json")

      val error = new StatementSuggestionsService(client)
        .resolve(
          statementId,
          model,
          List(shop),
          Nil,
          List(merchant("Corner Shop", Some(groceries.id))),
          categories,
          Nil
        )
        .failed
        .futureValue

      error mustBe a[RuntimeException]
      error.getMessage must include("not json")
    }

    "keep the predicted category when no merchant is chosen" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop", Some(groceries.id))
      val client = new StubBedrockClient(
        answer("categoryId", shop.id -> Some(groceries.id)),
        answer("merchantId", shop.id -> None)
      )

      val result = resolve(client, List(shop), List(cornerShop), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None)))
    }

    "skip the merchant step for a category without merchants" in {
      val bus = row("METRO TICKET")
      val client = new StubBedrockClient(answer("categoryId", bus.id -> Some(transport.id)))

      val result = resolve(client, List(bus), List(merchant("Corner Shop", Some(groceries.id))), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(bus.id, Some(transport.id), None)))
      client.prompts must have size 1
    }

    "drop a category id that is not one of the user's categories" in {
      val bus = row("METRO TICKET")
      val client = new StubBedrockClient(answer("categoryId", bus.id -> Some(UUID.randomUUID())))

      val result = resolve(client, List(bus), Nil, categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(bus.id, None, None)))
    }

    "drop a merchant id that is not one of the predicted category's merchants" in {
      val shop = row("CORNER SHOP")
      val cornerShop = merchant("Corner Shop", Some(groceries.id))
      val uber = merchant("Uber", Some(transport.id))
      val client = new StubBedrockClient(
        answer("categoryId", shop.id -> Some(groceries.id)),
        // Uber exists but is not one of the offered Groceries merchants.
        answer("merchantId", shop.id -> Some(uber.id))
      )

      val result = resolve(client, List(shop), List(cornerShop, uber), categories)

      result.rows must be(List(AiSuggestions.RowSuggestion(shop.id, Some(groceries.id), None)))
    }

    "leave a row missing from the answer without a suggestion" in {
      val shop = row("CORNER SHOP")
      val missing = row("UNKNOWN STORE")
      val client = new StubBedrockClient(answer("categoryId", shop.id -> Some(transport.id)))

      val result = resolve(client, List(shop, missing), Nil, categories)

      result.rows must be(
        List(
          AiSuggestions.RowSuggestion(shop.id, Some(transport.id), None),
          AiSuggestions.RowSuggestion(missing.id, None, None)
        )
      )
    }

    "split the rows of each step into batches of at most BatchSize" in {
      val rows = (0 to StatementSuggestionsService.BatchSize).toList.map(i => row(s"SHOP $i"))
      val (first, second) = rows.splitAt(StatementSuggestionsService.BatchSize)
      val cornerShop = merchant("Corner Shop", Some(groceries.id))
      val client = new StubBedrockClient(
        answer("categoryId", first.map(_.id -> Some(groceries.id))*),
        answer("categoryId", second.map(_.id -> Some(groceries.id))*),
        answer("merchantId", first.map(_.id -> Some(cornerShop.id))*),
        answer("merchantId", second.map(_.id -> Some(cornerShop.id))*)
      )

      val result = resolve(client, rows, List(cornerShop), categories)

      result.rows must be(rows.map(r => AiSuggestions.RowSuggestion(r.id, Some(groceries.id), Some(cornerShop.id))))
      client.prompts must have size 4
      List(client.prompts(0), client.prompts(2)).foreach { p =>
        p must include(first.last.id.toString)
        p must not include second.head.id.toString
      }
      List(client.prompts(1), client.prompts(3)).foreach { p =>
        p must include(second.head.id.toString)
        p must not include first.head.id.toString
      }
    }

    "fail when the model answer is not valid JSON or has no rows array" in {
      List("Sorry, I cannot help with that", """{"answer":[]}""").foreach { raw =>
        val client = new StubBedrockClient(raw)
        val error = new StatementSuggestionsService(client)
          .resolve(statementId, model, List(row("CORNER SHOP")), Nil, Nil, categories, Nil)
          .failed
          .futureValue
        error mustBe a[RuntimeException]
        error.getMessage must include(raw)
      }
    }

    "make no Bedrock call when there is nothing to resolve" in {
      val client = new StubBedrockClient()

      resolve(client, List(row("SALARY", BigDecimal(1000))), Nil, categories).rows must be(Nil)
      client.prompts must be(empty)
    }

    "make no Bedrock call when there are no categories" in {
      val shop = row("CORNER SHOP")
      val client = new StubBedrockClient()

      resolve(client, List(shop), Nil, Nil).rows must be(List(AiSuggestions.RowSuggestion(shop.id, None, None)))
      client.prompts must be(empty)
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
