package net.wiringbits.mcp

import net.wiringbits.actions.bankstatements.{GetAllRowsAction, GetBankStatementsAction}
import net.wiringbits.actions.categories.GetCategoriesAction
import net.wiringbits.actions.expenses.GetExpensesAction
import net.wiringbits.mcp.McpProtocol.*
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, JsValue, Json}
import sttp.model.StatusCode

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class McpDispatcher @Inject() (
    getExpensesAction: GetExpensesAction,
    getCategoriesAction: GetCategoriesAction,
    getBankStatementsAction: GetBankStatementsAction,
    getAllRowsAction: GetAllRowsAction
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, message: JsonRpcMessage): Future[(StatusCode, String)] = message match {
    case Notification(_, _) | ResponseMessage =>
      Future.successful((StatusCode.Accepted, ""))

    case Malformed(errCode, errMessage) =>
      Future.successful((StatusCode.BadRequest, Json.stringify(error(None, errCode, errMessage))))

    case Request(id, method, params) =>
      logger.info(s"MCP request: method=$method user=$userId")
      dispatchRequest(userId, id, method, params)
  }

  private def dispatchRequest(
      userId: UUID,
      id: JsValue,
      method: String,
      params: JsObject
  ): Future[(StatusCode, String)] = {
    method match {
      case "initialize" =>
        val requestedVersion = (params \ "protocolVersion").asOpt[String]
        val negotiatedVersion = requestedVersion.filter(SupportedVersions.contains).getOrElse(ProtocolVersion)
        val result = Json.obj(
          "protocolVersion" -> negotiatedVersion,
          "capabilities" -> Json.obj("tools" -> Json.obj("listChanged" -> false)),
          "serverInfo" -> Json.obj("name" -> ServerName, "title" -> "Expense Tracker", "version" -> ServerVersion),
          "instructions" -> ("Read-only access to the authenticated user's expense data. Use list_categories to " +
            "learn category ids, then list_expenses to fetch the full expense history; filter and aggregate locally. " +
            "For true spend (including activity that never became an expense), use list_bank_statement_rows, whose " +
            "signed amounts come straight from the bank statements.")
        )
        ok(id, result)

      case "ping" =>
        ok(id, Json.obj())

      case "tools/list" =>
        ok(id, McpTools.toolsList)

      case "tools/call" =>
        callTool(userId, id, params)

      case other =>
        Future.successful((StatusCode.Ok, Json.stringify(error(Some(id), -32601, s"Method not found: $other"))))
    }
  }

  private def callTool(userId: UUID, id: JsValue, params: JsObject): Future[(StatusCode, String)] = {
    (params \ "name").asOpt[String] match {
      case None =>
        rpcError(id, -32602, "Invalid params: 'name' is required")
      case Some(name) =>
        val hasArguments = (params \ "arguments").asOpt[JsObject].exists(_.value.nonEmpty)
        if (hasArguments) {
          rpcError(id, -32602, s"Tool $name accepts no arguments")
        } else {
          name match {
            case McpTools.ListExpenses =>
              toolResult(id, getExpensesAction(userId, None, None, false).map(Json.toJson(_)))
            case McpTools.ListCategories => toolResult(id, getCategoriesAction(userId).map(Json.toJson(_)))
            case McpTools.ListBankStatementRows =>
              val grouped = for {
                statements <- getBankStatementsAction(userId)
                rows <- getAllRowsAction(userId)
              } yield McpBankStatementRows.grouped(statements, rows)
              toolResult(id, grouped)
            case other => rpcError(id, -32602, s"Unknown tool: $other")
          }
        }
    }
  }

  private def toolResult(id: JsValue, result: Future[JsValue]): Future[(StatusCode, String)] = {
    result
      .map { json =>
        // Not also setting structuredContent: no outputSchema is declared for these tools (see
        // McpTools), so nothing validates against it, and it would just double the response size.
        val payload = Json.obj(
          "content" -> Json.arr(Json.obj("type" -> "text", "text" -> Json.stringify(json))),
          "isError" -> false
        )
        (StatusCode.Ok, Json.stringify(success(id, payload)))
      }
      .recover { case NonFatal(ex) =>
        logger.error(s"MCP tool call failed: ${ex.getMessage}")
        val payload = Json.obj(
          "content" -> Json.arr(Json.obj("type" -> "text", "text" -> ex.getMessage)),
          "isError" -> true
        )
        (StatusCode.Ok, Json.stringify(success(id, payload)))
      }
  }

  private def ok(id: JsValue, result: JsObject): Future[(StatusCode, String)] =
    Future.successful((StatusCode.Ok, Json.stringify(success(id, result))))

  private def rpcError(id: JsValue, code: Int, message: String): Future[(StatusCode, String)] =
    Future.successful((StatusCode.Ok, Json.stringify(error(Some(id), code, message))))
}
