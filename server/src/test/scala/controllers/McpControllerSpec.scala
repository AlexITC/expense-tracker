package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.github.f4b6a3.uuid.UuidCreator
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.repositories.models.BankStatementRow
import net.wiringbits.services.{BlobStorage, NoOpBlobStorage}
import net.wiringbits.util.TokenGenerator
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers.*
import utils.LoginUtils

import java.time.{Instant, LocalDate}
import java.util.UUID

class McpControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {
  private val tokenGenerator = mock[TokenGenerator]

  override def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    super
      .guiceApplicationBuilder(container)
      .overrides(
        inject.bind[TokenGenerator].to(tokenGenerator),
        inject.bind[BlobStorage].toInstance(new NoOpBlobStorage())
      )

  private def bearer(token: String): (String, String) = "Authorization" -> s"Bearer $token"

  private def rpc(method: String, id: JsValue = Json.toJson(1), params: JsValue = Json.obj()): String =
    Json.stringify(Json.obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params))

  // Creates a bank statement with one row with the given amount/description; returns the statement id.
  private def setupStatementWithRow(amount: BigDecimal, description: String)(implicit
      client: net.wiringbits.api.ApiClient
  ): UUID = {
    val now = Instant.now()
    val userId = client.currentUser.futureValue.id
    createBankStatementWithRows(
      userId,
      stmtId =>
        List(
          BankStatementRow.Create(
            UuidCreator.getTimeOrderedEpoch(),
            stmtId,
            0,
            LocalDate.of(2026, 1, 15),
            amount,
            description,
            manuallyEdited = false,
            now,
            now,
            txnType = "purchase"
          )
        )
    )
  }

  "POST /mcp" should {
    "return 401 when no Authorization header is present" in withApiClient { implicit client =>
      val response = POST("/mcp", rpc("initialize"))
      status(response) must be(UNAUTHORIZED)
    }

    "return 401 for an unknown token" in withApiClient { implicit client =>
      val response = POST("/mcp", rpc("initialize"), bearer("a" * 32))
      status(response) must be(UNAUTHORIZED)
    }

    "return 401 when the Authorization header has no Bearer prefix" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val token = client.createMcpToken.futureValue.token.value
      val response = POST("/mcp", rpc("initialize"), "Authorization" -> token)
      status(response) must be(UNAUTHORIZED)
    }

    "handle initialize" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val token = client.createMcpToken.futureValue.token.value

      val response = POST("/mcp", rpc("initialize"), bearer(token))
      status(response) must be(OK)
      val json = contentAsJson(response)
      (json \ "result" \ "serverInfo" \ "name").as[String] must be("expense-tracker")
      (json \ "result" \ "capabilities" \ "tools").isDefined must be(true)
    }

    "return 202 with an empty body for a notification" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val token = client.createMcpToken.futureValue.token.value

      val body = Json.stringify(Json.obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized"))
      val response = POST("/mcp", body, bearer(token))
      status(response) must be(ACCEPTED)
      contentAsString(response) must be("")
    }

    "list both tools" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val token = client.createMcpToken.futureValue.token.value

      val response = POST("/mcp", rpc("tools/list"), bearer(token))
      status(response) must be(OK)
      val names = (contentAsJson(response) \ "result" \ "tools").as[List[JsValue]].map(t => (t \ "name").as[String])
      names must contain.allOf("list_expenses", "list_categories", "list_bank_statement_rows")
    }

    "group rows under their statement via list_bank_statement_rows" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      setupStatementWithRow(BigDecimal("-50.00"), "COFFEE SHOP")
      val token = client.createMcpToken.futureValue.token.value

      val params = Json.obj("name" -> "list_bank_statement_rows")
      val response = POST("/mcp", rpc("tools/call", params = params), bearer(token))
      status(response) must be(OK)
      (contentAsJson(response) \ "result" \ "isError").as[Boolean] must be(false)

      val text = ((contentAsJson(response) \ "result" \ "content").as[List[JsValue]].head \ "text").as[String]
      val payload = Json.parse(text)
      val statements = (payload \ "statements").as[List[JsValue]]
      statements must have size 1
      (statements.head \ "documentFilename").as[String] must be("stmt.pdf")
      val rows = (statements.head \ "rows").as[List[JsValue]]
      rows must have size 1
      (rows.head \ "amount").as[BigDecimal] must be(BigDecimal("-50.00"))
      (rows.head \ "description").as[String] must be("COFFEE SHOP")
      (rows.head \ "bankStatementId").toOption must be(None)
    }

    "scope list_bank_statement_rows to the authenticated user" in withApiClient { implicit clientA =>
      createVerifyLoginUser(tokenGenerator).futureValue
      setupStatementWithRow(BigDecimal("-10.00"), "MINE")
      val tokenA = clientA.createMcpToken.futureValue.token.value

      withApiClient { implicit clientB =>
        createVerifyLoginUser(tokenGenerator).futureValue
        setupStatementWithRow(BigDecimal("-99.00"), "THEIRS")

        val params = Json.obj("name" -> "list_bank_statement_rows")
        val response = POST("/mcp", rpc("tools/call", params = params), bearer(tokenA))
        val text = ((contentAsJson(response) \ "result" \ "content").as[List[JsValue]].head \ "text").as[String]
        val descriptions =
          (Json.parse(text) \ "statements")
            .as[List[JsValue]]
            .flatMap(s => (s \ "rows").as[List[JsValue]])
            .map(r => (r \ "description").as[String])
        descriptions must be(List("MINE"))
      }
    }

    "return only the authenticated user's expenses via list_expenses" in withApiClient { implicit clientA =>
      createVerifyLoginUser(tokenGenerator).futureValue
      clientA.createExpense(CreateExpense.Request(42.0, LocalDate.now(), None, None, None, Nil)).futureValue
      val tokenA = clientA.createMcpToken.futureValue.token.value

      withApiClient { implicit clientB =>
        createVerifyLoginUser(tokenGenerator).futureValue
        clientB.createExpense(CreateExpense.Request(99.0, LocalDate.now(), None, None, None, Nil)).futureValue

        val params = Json.obj("name" -> "list_expenses")
        val response = POST("/mcp", rpc("tools/call", params = params), bearer(tokenA))
        status(response) must be(OK)
        val text = ((contentAsJson(response) \ "result" \ "content").as[List[JsValue]].head \ "text").as[String]
        val amounts = (Json.parse(text) \ "data").as[List[JsValue]].map(e => (e \ "amount").as[BigDecimal])
        amounts must be(List(BigDecimal(42.0)))
      }
    }

    "return the authenticated user's categories via list_categories" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val token = client.createMcpToken.futureValue.token.value

      val params = Json.obj("name" -> "list_categories")
      val response = POST("/mcp", rpc("tools/call", params = params), bearer(token))
      status(response) must be(OK)
      val text = ((contentAsJson(response) \ "result" \ "content").as[List[JsValue]].head \ "text").as[String]
      val names = (Json.parse(text) \ "data").as[List[JsValue]].map(c => (c \ "name").as[String])
      names must be(List("Restaurants"))
    }

    "return a JSON-RPC error for an unknown tool" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val token = client.createMcpToken.futureValue.token.value

      val params = Json.obj("name" -> "delete_everything")
      val response = POST("/mcp", rpc("tools/call", params = params), bearer(token))
      status(response) must be(OK)
      (contentAsJson(response) \ "error" \ "code").as[Int] must be(-32602)
    }

    "return 400 for an unsupported MCP-Protocol-Version" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val token = client.createMcpToken.futureValue.token.value

      val response = POST("/mcp", rpc("initialize"), bearer(token), "MCP-Protocol-Version" -> "2099-01-01")
      status(response) must be(BAD_REQUEST)
    }
  }

  "GET /mcp" should {
    "return 405" in withApiClient { _ =>
      val response = GET("/mcp")
      status(response) must be(METHOD_NOT_ALLOWED)
    }
  }
}
