package net.wiringbits.scripts

import net.wiringbits.apis.RealBedrockClient
import net.wiringbits.config.BedrockConfig
import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import net.wiringbits.services.{AiStatementExtractor, PdfTextExtractor}
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region

import java.nio.file.{Files, Paths}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Proves the PRODUCTION code path end-to-end (PdfTextExtractor -> RealBedrockClient.extractBankStatement ->
  * AiStatementExtractor) on real statement PDFs, before wiring is trusted. Run: sbt "server/runMain
  * net.wiringbits.scripts.AiExtractionProof <pdf> [<pdf> ...]"
  */
object AiExtractionProof {
  def main(args: Array[String]): Unit = {
    val creds = DefaultCredentialsProvider.builder().build().resolveCredentials()
    val config = BedrockConfig(
      AWSAccessKeyId(creds.accessKeyId()),
      AWSSecretAccessKey(creds.secretAccessKey()),
      Region.US_EAST_1,
      "amazon.nova-pro-v1:0",
      sys.env.getOrElse("BEDROCK_STATEMENT_MODEL_ID", "us.anthropic.claude-sonnet-4-5-20250929-v1:0"),
      sys.env.getOrElse("BEDROCK_STATEMENT_MAX_TOKENS", "16000").toInt,
      BedrockConfig.Real
    )
    val client = new RealBedrockClient(config)
    args.foreach { path =>
      val name = path.split('/').lastOption.getOrElse(path)
      val result = for {
        text <- PdfTextExtractor.extract(Files.readAllBytes(Paths.get(path))) match {
          case Right(t) => Right(t)
          case Left(e) => Left(e)
        }
      } yield {
        val raw = Await.result(client.extractBankStatement(text), 300.seconds)
        AiStatementExtractor.parseAndVerify(raw, config.statementModelId)
      }
      result match {
        case Right(Right(r)) =>
          println(
            s"\n### $name: reconciled=${r.reconciled} txns=${r.transactions.size} bank=${r.metadata.bankName} " +
              s"product=${r.metadata.productType} currency=${r.metadata.currency} " +
              s"period=${r.metadata.periodStart.getOrElse("-")}..${r.metadata.periodEnd.getOrElse("-")} " +
              s"discrepancy=${r.discrepancy.getOrElse("-")}"
          )
          r.transactions.foreach { t =>
            val flag = if (t.txnType.exists(_ != "purchase")) s"  <-- ${t.txnType.getOrElse("?")}" else ""
            println(f"  ${t.date}%s  ${t.amount}%10s  ${t.txnType.getOrElse("-")}%-9s ${t.description}%s$flag%s")
          }
        case Right(Left(e)) => println(s"$name: EXTRACT/VERIFY ERROR: $e")
        case Left(e) => println(s"$name: PDF ERROR: $e")
      }
    }
    sys.exit(0)
  }
}
