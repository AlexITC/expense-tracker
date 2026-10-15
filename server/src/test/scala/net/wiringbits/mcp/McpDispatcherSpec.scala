package net.wiringbits.mcp

import net.wiringbits.actions.bankstatements.{GetAllRowsAction, GetBankStatementsAction}
import net.wiringbits.actions.categories.GetCategoriesAction
import net.wiringbits.actions.expenses.GetExpensesAction
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsNumber, JsString, Json}
import sttp.model.StatusCode

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class McpDispatcherSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val userId = UUID.randomUUID()

  private def newDispatcher(
      getExpensesAction: GetExpensesAction = mock[GetExpensesAction],
      getCategoriesAction: GetCategoriesAction = mock[GetCategoriesAction],
      getBankStatementsAction: GetBankStatementsAction = mock[GetBankStatementsAction],
      getAllRowsAction: GetAllRowsAction = mock[GetAllRowsAction]
  ) = new McpDispatcher(getExpensesAction, getCategoriesAction, getBankStatementsAction, getAllRowsAction)(using global)

  "initialize" should {
    "echo a supported protocolVersion and declare only the tools capability" in {
      val dispatcher = newDispatcher()
      val params = Json.obj("protocolVersion" -> "2025-06-18")
      val (status, body) = dispatcher(userId, McpProtocol.Request(JsNumber(1), "initialize", params)).futureValue

      status must be(StatusCode.Ok)
      val json = Json.parse(body)
      (json \ "result" \ "protocolVersion").as[String] must be("2025-06-18")
      (json \ "result" \ "serverInfo" \ "name").as[String] must be("expense-tracker")
      (json \ "result" \ "capabilities").as[Map[String, play.api.libs.json.JsValue]].keySet must be(Set("tools"))
    }

    "fall back to the current protocol version when the requested one is unsupported" in {
      val dispatcher = newDispatcher()
      val params = Json.obj("protocolVersion" -> "1999-01-01")
      val (_, body) = dispatcher(userId, McpProtocol.Request(JsNumber(1), "initialize", params)).futureValue

      (Json.parse(body) \ "result" \ "protocolVersion").as[String] must be(McpProtocol.ProtocolVersion)
    }
  }

  "a request id" should {
    "preserve its JSON type when echoed back" in {
      val dispatcher = newDispatcher()

      val (_, numericBody) = dispatcher(userId, McpProtocol.Request(JsNumber(7), "ping", Json.obj())).futureValue
      (Json.parse(numericBody) \ "id").as[Int] must be(7)

      val (_, stringBody) =
        dispatcher(userId, McpProtocol.Request(JsString("abc"), "ping", Json.obj())).futureValue
      (Json.parse(stringBody) \ "id").as[String] must be("abc")
    }
  }

  "a notification" should {
    "return 202 with an empty body regardless of method" in {
      val dispatcher = newDispatcher()
      val (status, body) =
        dispatcher(userId, McpProtocol.Notification("notifications/initialized", Json.obj())).futureValue

      status must be(StatusCode.Accepted)
      body must be("")
    }
  }

  "a response message" should {
    "return 202 with an empty body" in {
      val dispatcher = newDispatcher()
      val (status, body) = dispatcher(userId, McpProtocol.ResponseMessage).futureValue

      status must be(StatusCode.Accepted)
      body must be("")
    }
  }

  "a malformed message" should {
    "return 400 with the parse error code" in {
      val dispatcher = newDispatcher()
      val (status, body) = dispatcher(userId, McpProtocol.Malformed(-32700, "Parse error")).futureValue

      status must be(StatusCode.BadRequest)
      (Json.parse(body) \ "error" \ "code").as[Int] must be(-32700)
    }
  }

  "tools/list" should {
    "return list_expenses, list_categories and list_bank_statement_rows" in {
      val dispatcher = newDispatcher()
      val (status, body) = dispatcher(userId, McpProtocol.Request(JsNumber(1), "tools/list", Json.obj())).futureValue

      status must be(StatusCode.Ok)
      val names =
        (Json.parse(body) \ "result" \ "tools").as[List[play.api.libs.json.JsValue]].map(t => (t \ "name").as[String])
      names must be(List("list_expenses", "list_categories", "list_bank_statement_rows"))
    }
  }

  // NOTE: the list_bank_statement_rows success path is covered end-to-end in McpControllerSpec (booted app), not here.
  // Constructing GetBankStatements.Response/GetAllRows.Response in an isolated JVM triggers the same pre-existing
  // tapir Schema.derived static-init bug documented above for GetExpenses/GetCategories.

  // NOTE: the success-path "call list_expenses/list_categories and wrap the result" cases are NOT covered
  // here. Constructing a real GetExpenses.Response/GetCategories.Response and running it through Json.toJson
  // triggers a pre-existing static-initialization bug in their Schema derivation (a magnolia/tapir circular
  // reference between the outer Response schema and the nested Expense/Category schema) whenever those
  // classes are touched for the first time in an isolated JVM outside a booted Play app — reproduced
  // independently of this change, unrelated to McpDispatcher. It does not happen in the real server, because
  // ApiRouter's Swagger doc generation forces safe initialization at boot before any request is served; this
  // is confirmed by McpControllerSpec, which calls list_expenses/list_categories through a real booted app
  // and real serialization successfully. Fixing the schema derivation itself is out of scope for this ticket.
  "tools/call" should {
    "return a JSON-RPC error for an unknown tool name" in {
      val dispatcher = newDispatcher()
      val params = Json.obj("name" -> "delete_everything")
      val (status, body) =
        dispatcher(userId, McpProtocol.Request(JsNumber(1), "tools/call", params)).futureValue

      status must be(StatusCode.Ok)
      (Json.parse(body) \ "error" \ "code").as[Int] must be(-32602)
    }

    "return a JSON-RPC error when name is missing" in {
      val dispatcher = newDispatcher()
      val (status, body) =
        dispatcher(userId, McpProtocol.Request(JsNumber(1), "tools/call", Json.obj())).futureValue

      status must be(StatusCode.Ok)
      (Json.parse(body) \ "error" \ "code").as[Int] must be(-32602)
    }

    "return a JSON-RPC error when non-empty arguments are supplied" in {
      val dispatcher = newDispatcher()
      val params = Json.obj("name" -> "list_expenses", "arguments" -> Json.obj("from" -> "2026-01-01"))
      val (status, body) =
        dispatcher(userId, McpProtocol.Request(JsNumber(1), "tools/call", params)).futureValue

      status must be(StatusCode.Ok)
      (Json.parse(body) \ "error" \ "code").as[Int] must be(-32602)
    }

    "return isError: true, not a JSON-RPC error, when the underlying action fails" in {
      val expensesAction = mock[GetExpensesAction]
      when(expensesAction(any[UUID]())).thenReturn(Future.failed(new RuntimeException("boom")))
      val dispatcher = newDispatcher(getExpensesAction = expensesAction)

      val params = Json.obj("name" -> "list_expenses")
      val (status, body) =
        dispatcher(userId, McpProtocol.Request(JsNumber(1), "tools/call", params)).futureValue

      status must be(StatusCode.Ok)
      val json = Json.parse(body)
      (json \ "error").toOption must be(None)
      (json \ "result" \ "isError").as[Boolean] must be(true)
      (json \ "result" \ "content" \ 0 \ "text").as[String] must include("boom")
    }
  }

  "an unknown method" should {
    "return -32601 method not found" in {
      val dispatcher = newDispatcher()
      val (status, body) =
        dispatcher(userId, McpProtocol.Request(JsNumber(1), "resources/list", Json.obj())).futureValue

      status must be(StatusCode.Ok)
      (Json.parse(body) \ "error" \ "code").as[Int] must be(-32601)
    }
  }
}
