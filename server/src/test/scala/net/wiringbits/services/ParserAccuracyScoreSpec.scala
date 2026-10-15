package net.wiringbits.services

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, Json}

/** Regression gate for DocumentParser accuracy against a labeled dataset of real receipts.
  *
  * Each entry pairs a raw Textract `AnalyzeExpense` response (`responses/<id>.json`) with the amount and date the user
  * confirmed for it (`labels.json`). The confirmed amount is the value that reconciled against the bank statement, so a
  * high score here is a direct proxy for how well receipts auto-reconcile.
  *
  * Unlike `DocumentParserSpec` (which pins exact parsed output for 27 fixtures), this spec scores aggregate accuracy
  * and fails only when the number of mismatches exceeds the allowed budget below — so it catches regressions without
  * being tied to the full parsed shape.
  *
  * To extend the dataset, dump a prod database and re-export (see `scripts.ParserAccuracyReport` for the queries): drop
  * each receipt's Textract response in `responses/<receiptId>.json` and add its confirmed amount/date to `labels.json`.
  * If you add a receipt the parser genuinely cannot get right (e.g. unreadable OCR), raise the matching budget below
  * with a comment explaining why, rather than silently loosening the gate.
  */
class ParserAccuracyScoreSpec extends AnyWordSpec {

  private val MaxAmountFailures = 0
  private val MaxDateFailures = 0

  private case class Label(id: String, amount: BigDecimal, date: String)

  private def loadResource(path: String): String = {
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    require(stream != null, s"missing resource: $path")
    try new String(stream.readAllBytes(), "UTF-8")
    finally stream.close()
  }

  private val labels: List[Label] =
    Json.parse(loadResource("fixtures/parser-accuracy/labels.json")).as[List[JsObject]].map { o =>
      Label((o \ "id").as[String], BigDecimal((o \ "amount").as[String]), (o \ "date").as[String])
    }

  "DocumentParser accuracy (labeled receipts)" should {
    "match the confirmed amount and date within the allowed failure budget" in {
      val results = labels.map { label =>
        val response = Json.parse(loadResource(s"fixtures/parser-accuracy/responses/${label.id}.json"))
        val parsed = DocumentParser.parse(response, "receipt").toOption.get
        (label, parsed)
      }
      val n = results.size

      val amountFailures = results.filterNot { case (l, p) => p.amount.contains(l.amount) }
      val dateFailures = results.filterNot { case (l, p) => p.transaction_date.contains(l.date) }

      info(s"amount: ${n - amountFailures.size}/$n matched; date: ${n - dateFailures.size}/$n matched")

      val amountMsg = amountFailures
        .map { case (l, p) => s"${l.id.take(8)} expected=${l.amount} got=${p.amount.map(_.toString).getOrElse("-")}" }
        .mkString("; ")
      val dateMsg = dateFailures
        .map { case (l, p) => s"${l.id.take(8)} expected=${l.date} got=${p.transaction_date.getOrElse("-")}" }
        .mkString("; ")

      withClue(s"amount mismatches: $amountMsg\n")(amountFailures.size must be <= MaxAmountFailures)
      withClue(s"date mismatches: $dateMsg\n")(dateFailures.size must be <= MaxDateFailures)
    }
  }
}
