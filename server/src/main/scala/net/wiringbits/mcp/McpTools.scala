package net.wiringbits.mcp

import play.api.libs.json.{JsObject, Json}

object McpTools {

  val ListExpenses = "list_expenses"
  val ListCategories = "list_categories"
  val ListBankStatementRows = "list_bank_statement_rows"
  val ListSubscriptions = "list_subscriptions"

  private val noArgsSchema: JsObject = Json.obj("type" -> "object", "additionalProperties" -> false)

  val toolsList: JsObject = Json.obj(
    "tools" -> Json.arr(
      Json.obj(
        "name" -> ListExpenses,
        "title" -> "List expenses",
        "description" -> ("Returns every expense recorded by the authenticated user: amount, date, optional time, " +
          "merchant name, notes, and per-category splits. Returns the complete history with no server-side " +
          "filtering — filter and aggregate the results yourself."),
        "inputSchema" -> noArgsSchema
      ),
      Json.obj(
        "name" -> ListCategories,
        "title" -> "List categories",
        "description" -> ("Returns every expense category belonging to the authenticated user. The returned " +
          "category ids are the ones referenced by the splits in list_expenses. Each category may include an " +
          "optional description of what kind of expense belongs in it."),
        "inputSchema" -> noArgsSchema
      ),
      Json.obj(
        "name" -> ListBankStatementRows,
        "title" -> "List bank statement rows",
        "description" -> ("Returns every parsed bank-statement row for the authenticated user, grouped under the " +
          "statement it came from. Each row has a date, a signed amount (NEGATIVE = money out / debit, POSITIVE = " +
          "money in / credit), a description, an optional expenseId (set when the row was reconciled into an " +
          "expense; unset means it is real activity that never became an expense), and an optional ignoredAt (set " +
          "when the user excluded the row). Unlike list_expenses, this reflects actual bank activity and is the " +
          "source of truth for real spend. Complete history, no server-side filtering — filter and aggregate " +
          "yourself."),
        "inputSchema" -> noArgsSchema
      ),
      Json.obj(
        "name" -> ListSubscriptions,
        "title" -> "List subscriptions",
        "description" -> ("Returns every subscription of the authenticated user: name, cadence (plus intervalDays " +
          "or intervalMonths when the cadence needs one), payment method, optional description, optional " +
          "merchantId (as in list_expenses) and optional categoryId (as in list_categories). Each subscription " +
          "lists its terms (effectiveFrom, optional effectiveTo which is EXCLUSIVE, and an optional amountMin / " +
          "amountMax range); when no term is open (every term has an effectiveTo) the subscription is cancelled. " +
          "Each subscription also lists its extra charges. Complete list, no server-side filtering — filter and " +
          "aggregate yourself."),
        "inputSchema" -> noArgsSchema
      )
    )
  )
}
