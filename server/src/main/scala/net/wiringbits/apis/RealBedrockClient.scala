package net.wiringbits.apis

import net.wiringbits.config.BedrockConfig
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseRequest,
  ConverseResponse,
  InferenceConfiguration,
  Message,
  StopReason
}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*

class RealBedrockClient(config: BedrockConfig)(implicit ec: ExecutionContext) extends BedrockClient {

  private val SuggestionsMaxTokens = 4096

  private val client: BedrockRuntimeAsyncClient = {
    val credentials = AwsBasicCredentials.create(config.accessKeyId.string, config.secretAccessKey.string)
    BedrockRuntimeAsyncClient
      .builder()
      .region(config.region)
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      // A large statement makes the model generate for well over the 30s default read timeout.
      .httpClientBuilder(NettyNioAsyncHttpClient.builder().readTimeout(java.time.Duration.ofSeconds(120)))
      .build()
  }

  override def extractBankStatement(statementText: String, feedback: Option[String] = None): Future[String] = {
    val message = Message
      .builder()
      .role(ConversationRole.USER)
      .content(ContentBlock.fromText(buildExtractionPrompt(statementText, feedback)))
      .build()
    val request = ConverseRequest
      .builder()
      .modelId(config.statementModelId)
      .messages(message)
      .inferenceConfig(
        InferenceConfiguration.builder().maxTokens(config.statementMaxTokens).temperature(0.0f).build()
      )
      .build()
    client.converse(request).asScala.map { response =>
      if (response.stopReason() == StopReason.MAX_TOKENS)
        throw new RuntimeException(
          s"Bedrock response was truncated at the ${config.statementMaxTokens}-token limit " +
            s"(statement too large for one response); raise bedrock.statementMaxTokens (BEDROCK_STATEMENT_MAX_TOKENS)"
        )
      firstText(response)
    }
  }

  override def suggest(prompt: String): Future[String] = {
    val message = Message
      .builder()
      .role(ConversationRole.USER)
      .content(ContentBlock.fromText(prompt))
      .build()
    val request = ConverseRequest
      .builder()
      .modelId(config.suggestionsModelId)
      .messages(message)
      .inferenceConfig(InferenceConfiguration.builder().maxTokens(SuggestionsMaxTokens).temperature(0.0f).build())
      .build()
    client.converse(request).asScala.map { response =>
      if (response.stopReason() == StopReason.MAX_TOKENS)
        throw new RuntimeException(
          s"Bedrock suggestions response was truncated at the $SuggestionsMaxTokens-token limit"
        )
      firstText(response)
    }
  }

  private def firstText(response: ConverseResponse): String = {
    val content = response.output().message().content()
    (0 until content.size())
      .flatMap(i => Option(content.get(i).text()).filter(_.nonEmpty))
      .headOption
      .getOrElse(throw new RuntimeException(s"Bedrock returned no text content; stopReason=${response.stopReason()}"))
  }

  private def buildExtractionPrompt(text: String, feedback: Option[String]): String = {
    val correction = feedback
      .map(f => s"""
       |## Correction
       |A previous attempt was rejected: $f
       |Re-extract the FULL statement carefully and return ONLY the corrected, complete JSON. Common causes: a
       |transaction is missing or duplicated, a foreign-currency settled amount / commission line is misread, or the
       |output was not valid JSON.
       |""".stripMargin)
      .getOrElse("")
    s"""You are given a single bank or credit-card statement as extracted text. Extract its data.
       |$correction
       |## Statement text
       |$text
       |
       |## Task
       |Return ONLY a JSON object (no prose, no markdown fences):
       |{
       |  "metadata": {
       |    "bankName": string|null, "productType": "credit"|"debit"|null, "currency": string|null,
       |    "accountLast4": string|null, "periodStart": "YYYY-MM-DD"|null, "periodEnd": "YYYY-MM-DD"|null,
       |    "periodMonth": "YYYY-MM"|null, "openingBalance": number|null, "closingBalance": number|null
       |  },
       |  "transactions": [
       |    { "date":"YYYY-MM-DD", "amount": number, "description": string, "runningBalance": number|null,
       |      "type": "purchase"|"payment"|"fee"|"interest"|"refund" }
       |  ]
       |}
       |
       |Rules:
       |- Extract EVERY transaction from EVERY section (all cardholders, fees, interest, financial transactions). Do not
       |  skip, merge, or summarise. Exclude only non-transaction lines (headers, titles, subtotals, and the opening/
       |  previous-balance line).
       |- amount is SIGNED: negative = money out (a charge/debit), positive = money in (a payment/credit). A trailing
       |  credit marker (e.g. "CR") is positive; a line that pays down the previous balance is positive.
       |- description: the FULL text printed for the transaction, EXACTLY as it appears, including the business name AND
       |  any location/city. A transaction may span several lines: include every continuation/detail line that follows the
       |  dated line until the next dated transaction (itinerary, flight, passenger, reference lines), joined with "\n".
       |  Never shorten it to just the location/city — keep the full merchant name. Do not add or infer text.
       |- type: classify the row — "payment" (a payment of the card/account balance, e.g. a direct debit of the bill),
       |  "fee", "interest", "refund" (money returned), otherwise "purchase".
       |- RECONCILIATION (critical): the statement's balance must hold — openingBalance + sum(ALL transaction amounts) =
       |  closingBalance. Before returning, compute that sum and verify it equals closingBalance; if it does not, you have
       |  missed, duplicated, or mis-signed a row — fix it. If the statement prints subtotals (a total of charges and a
       |  total of payments/credits), make your signed charges tie to the charges total and your payments/credits tie to
       |  the payments total.
       |- SIGN comes from WHERE the statement places the amount — in its charges/debits column or its payments/credits
       |  column (or a trailing credit marker) — NOT from the words in the description. An amount placed as a charge
       |  increases the balance owed and is NEGATIVE; an amount placed as a payment/credit is money in and is POSITIVE.
       |  Apply this even when the wording suggests the opposite: a line printed in the charges column is negative even if
       |  its text reads like a refund, a reimbursement, or a "payment"; card fees and interest are negative. The only
       |  positive rows are those the statement places in its payments/credits column, a line that pays down the previous
       |  balance, and genuine refunds shown as credits. Emit EVERY row — purchases, fees, interest, reimbursements,
       |  balance payments — none are excluded.
       |- Emit each transaction ONCE if it is printed once. Some statements repeat a charge in both a per-cardholder
       |  summary and a detailed list — that is one charge. But two separate rows with the same amount ARE two
       |  transactions; keep both.
       |- amount: the one home-currency total printed for the transaction, EXACTLY as shown (do not recompute it). A
       |  converted/foreign transaction's row also shows the foreign amount, an exchange rate and a conversion fee; the
       |  printed total ALREADY INCLUDES that fee, so use it unchanged — never use the foreign amount, and do NOT add the
       |  fee to it. (e.g. a charge converting to 149.52 with a 4.34 fee is printed as 153.86, already including the fee:
       |  record 153.86, never 158.20.) Format as JSON numbers, dot decimal, no thousands separator ("3.000,00" ->
       |  3000.00, "-312,96" -> -312.96).
       |- runningBalance: the balance printed after each row ONLY if the statement shows one per row; else null.
       |- productType: "credit" for a credit card (credit limit / card number), "debit" for a bank/current account (IBAN).
       |- period: if the statement prints an explicit date range, set periodStart/periodEnd and leave periodMonth null. If it
       |  states only a billing month (e.g. "Periodo Liquidación: 04-2026"), set periodMonth "YYYY-MM" and leave the dates null.
       |- openingBalance/closingBalance: the previous and final/new balances if shown.
       |""".stripMargin
  }
}
