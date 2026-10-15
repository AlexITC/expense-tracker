package net.wiringbits.mcp

import net.wiringbits.api.models.bankstatements.{GetAllRows, GetBankStatements}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

/** Shapes the user's bank-statement activity for MCP: every parsed row grouped under the statement it came from. The
  * result is a single JSON object so an assistant can see all raw bank activity (including rows that never became an
  * expense) and analyse true spend.
  */
object McpBankStatementRows {

  private def rowJson(row: GetAllRows.RowData): JsObject =
    // bankStatementId is dropped — it's implied by the grouping.
    Json.toJson(row).as[JsObject] - "bankStatementId"

  def grouped(statements: GetBankStatements.Response, rows: GetAllRows.Response): JsValue = {
    val rowsByStatement = rows.data.groupBy(_.bankStatementId)

    val statementsJson = statements.data.map { statement =>
      val statementRows = rowsByStatement.getOrElse(statement.id, Nil).map(rowJson)
      Json.toJson(statement).as[JsObject] + ("rows" -> JsArray(statementRows))
    }

    val knownStatementIds = statements.data.map(_.id).toSet
    val orphanRows = rows.data.filterNot(row => knownStatementIds.contains(row.bankStatementId)).map(rowJson)

    val base = Json.obj("statements" -> JsArray(statementsJson))
    if (orphanRows.isEmpty) base else base + ("rowsWithoutStatement" -> JsArray(orphanRows))
  }
}
