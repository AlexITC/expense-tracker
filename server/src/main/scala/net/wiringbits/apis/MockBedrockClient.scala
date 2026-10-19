package net.wiringbits.apis

import scala.concurrent.Future

class MockBedrockClient extends BedrockClient {
  override def extractBankStatement(statementText: String, feedback: Option[String] = None): Future[String] =
    // A minimal statement that reconciles (opening 100 + (-40) = closing 60), for tests.
    Future.successful(
      """{"metadata":{"bankName":"MockBank","productType":"debit","currency":"EUR","accountLast4":"1234",""" +
        """"periodStart":"2026-01-01","periodEnd":"2026-01-31","openingBalance":100.00,"closingBalance":60.00},""" +
        """"transactions":[{"date":"2026-01-15","amount":-40.00,"description":"MOCK PURCHASE","runningBalance":60.00}]}"""
    )

  // A valid answer that matches no candidate, so mock-mode suggestion jobs complete without suggestions.
  override def suggest(prompt: String): Future[String] =
    Future.successful("""{"answer":"none"}""")
}
