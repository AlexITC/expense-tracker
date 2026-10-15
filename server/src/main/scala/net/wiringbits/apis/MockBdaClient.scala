package net.wiringbits.apis

import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future

/** Canned BDA responses for tests: a markdown document that reconciles (opening 100 + (-40) = closing 60), matching
  * MockBedrockClient, and a minimal custom-output JSON.
  */
class MockBdaClient extends BdaClient {

  override def standardMarkdown(storageKey: String): Future[Option[String]] =
    Future.successful(
      Some(
        """# Statement
          |
          |Saldo Anterior 100.00  Saldo Actual 60.00
          |
          || Date | Description | Amount |
          || 2026-01-15 | MOCK PURCHASE | -40.00 |
          |""".stripMargin
      )
    )

  override def customExtraction(storageKey: String): Future[Option[JsValue]] =
    Future.successful(
      Some(
        Json.obj(
          "statement_start_date" -> "2026-01-01",
          "statement_end_date" -> "2026-01-31",
          "transaction_details" -> Json.arr(
            Json.obj(
              "date" -> "2026-01-15",
              "description" -> "MOCK PURCHASE",
              "withdrawals" -> 40.0,
              "deposits" -> "",
              "balance" -> 60.0
            )
          )
        )
      )
    )
}
