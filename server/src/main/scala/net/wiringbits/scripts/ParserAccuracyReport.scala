package net.wiringbits.scripts

import anorm.*
import net.wiringbits.apis.TextractApiType
import net.wiringbits.repositories.daos.{DocumentDAO, ExpenseDAO, ReceiptDAO, TextractCacheDAO}
import net.wiringbits.repositories.models.{Expense, Receipt}
import net.wiringbits.services.DocumentParser

import java.sql.{Connection, DriverManager}
import java.time.LocalDate
import java.util.UUID
import scala.util.{Try, Using}

/** Read-only parser-accuracy harness.
  *
  * For every receipt that the user confirmed into an expense (`receipt.expense_id IS NOT NULL`), it re-runs the current
  * [[DocumentParser]] against the raw Textract response cached in `textract_cache` and compares the result, field by
  * field, to the confirmed expense (the human-validated ground truth).
  *
  * Each non-matching field is further classified by scanning the raw Textract JSON for the confirmed value:
  *   - RECOVERABLE — the confirmed value is present somewhere in the raw response, so the parser could have extracted
  *     it (a parser bug, fixable with no AWS work).
  *   - ABSENT — the confirmed value is nowhere in the raw response (Textract genuinely missed it, OR the user entered a
  *     value that was never on the document). Only this bucket could ever justify Textract Custom Queries adapters.
  *
  * Merchant, tax id and time are intentionally NOT scored: the confirmed merchant is a curated entity label (not the
  * raw vendor string), and expenses carry no tax id. Only amount and date have a fair confirmed counterpart.
  *
  * Not wired into CI — it needs a real database with data. Run on demand:
  * {{{
  *   ANALYSIS_DB_URL="jdbc:postgresql://127.0.0.1:5432/parser_analysis?user=ubuntu&password=..." \
  *     sbt "server/runMain net.wiringbits.scripts.ParserAccuracyReport"
  * }}}
  */
object ParserAccuracyReport {

  private sealed trait Status
  private case object Match extends Status // parser value equals confirmed value
  private case object Mismatch extends Status // parser produced a different value
  private case object Missed extends Status // parser produced nothing

  private case class FieldOutcome(status: Status, recoverable: Boolean)

  private case class FieldStats(
      matched: Int = 0,
      mismatchRecoverable: Int = 0,
      mismatchAbsent: Int = 0,
      missedRecoverable: Int = 0,
      missedAbsent: Int = 0
  ) {
    def record(o: FieldOutcome): FieldStats = o.status match {
      case Match => copy(matched = matched + 1)
      case Mismatch =>
        if (o.recoverable) copy(mismatchRecoverable = mismatchRecoverable + 1)
        else copy(mismatchAbsent = mismatchAbsent + 1)
      case Missed =>
        if (o.recoverable) copy(missedRecoverable = missedRecoverable + 1)
        else copy(missedAbsent = missedAbsent + 1)
    }
    def scored: Int = matched + mismatchRecoverable + mismatchAbsent + missedRecoverable + missedAbsent
    def wrong: Int = mismatchRecoverable + mismatchAbsent + missedRecoverable + missedAbsent
    def recoverable: Int = mismatchRecoverable + missedRecoverable
    def absent: Int = mismatchAbsent + missedAbsent
  }

  private case class Acc(
      noCache: Int = 0,
      noExpense: Int = 0,
      parseFailed: Int = 0,
      storedReproMismatch: Int = 0,
      amount: FieldStats = FieldStats(),
      date: FieldStats = FieldStats(),
      amountExamples: List[String] = Nil,
      dateExamples: List[String] = Nil
  )

  def main(args: Array[String]): Unit = {
    val url = sys.env.getOrElse(
      "ANALYSIS_DB_URL",
      "jdbc:postgresql://127.0.0.1:5432/parser_analysis?user=ubuntu&password=analysis"
    )
    Class.forName("org.postgresql.Driver")
    Using.resource(DriverManager.getConnection(url)) { implicit conn =>
      run()
    }
  }

  private def run()(implicit conn: Connection): Unit = {
    val userIds = SQL"SELECT DISTINCT user_id::text AS user_id FROM receipt"
      .as(SqlParser.str("user_id").*)
      .map(UUID.fromString)

    val labeled: List[(UUID, Receipt)] = userIds.flatMap { userId =>
      ReceiptDAO.all(userId).filter(_.expenseId.isDefined).map(userId -> _)
    }

    val acc = labeled.foldLeft(Acc()) { case (acc, (userId, receipt)) =>
      val docOpt = DocumentDAO.findById(receipt.documentId)
      val cacheOpt = docOpt.flatMap(d => TextractCacheDAO.find(d.sha256, TextractApiType.AnalyzeExpense))
      val expenseOpt = receipt.expenseId.flatMap(eid => ExpenseDAO.find(eid, userId))

      (cacheOpt, expenseOpt) match {
        case (None, _) => acc.copy(noCache = acc.noCache + 1)
        case (_, None) => acc.copy(noExpense = acc.noExpense + 1)
        case (Some(cache), Some(expense)) =>
          DocumentParser.parse(cache.response, "receipt") match {
            case Left(_) => acc.copy(parseFailed = acc.parseFailed + 1)
            case Right(parsed) =>
              val raw = cache.response.toString
              val shortId = receipt.id.toString.take(8)
              val reAmt = parsed.amount.filter(_ != 0)
              val repro = if (reAmt != receipt.amount.filter(_ != 0)) 1 else 0

              val parsedAmt = parsed.amount.filter(_ != 0)
              val amtOutcome = compareAmount(parsedAmt, expense.amount, raw)
              val amtExample =
                if (amtOutcome.status == Match) None
                else
                  Some(
                    f"$shortId  parsed=${parsedAmt.map(_.toString).getOrElse("-")}%-10s confirmed=${expense.amount}%-10s ${bucketLabel(amtOutcome)}"
                  )

              val parsedDate = parsed.transaction_date.flatMap(s => Try(LocalDate.parse(s)).toOption)
              val dateOutcome = compareDate(parsedDate, expense.date, raw)
              val dateExample =
                if (dateOutcome.status == Match) None
                else
                  Some(
                    f"$shortId  parsed=${parsedDate.map(_.toString).getOrElse("-")}%-12s confirmed=${expense.date}%-12s ${bucketLabel(dateOutcome)}"
                  )

              acc.copy(
                storedReproMismatch = acc.storedReproMismatch + repro,
                amount = acc.amount.record(amtOutcome),
                date = acc.date.record(dateOutcome),
                amountExamples = acc.amountExamples ++ amtExample.toList,
                dateExamples = acc.dateExamples ++ dateExample.toList
              )
          }
      }
    }

    printReport(labeled.size, acc)
  }

  private def compareAmount(parsed: Option[BigDecimal], confirmed: BigDecimal, raw: String): FieldOutcome = {
    val present = amountCandidates(confirmed).exists(raw.contains)
    parsed match {
      case None => FieldOutcome(Missed, present)
      case Some(p) if p == confirmed => FieldOutcome(Match, recoverable = true)
      case Some(_) => FieldOutcome(Mismatch, present)
    }
  }

  private def compareDate(parsed: Option[LocalDate], confirmed: LocalDate, raw: String): FieldOutcome = {
    val present = dateCandidates(confirmed).exists(raw.contains)
    parsed match {
      case None => FieldOutcome(Missed, present)
      case Some(p) if p == confirmed => FieldOutcome(Match, recoverable = true)
      case Some(_) => FieldOutcome(Mismatch, present)
    }
  }

  private def amountCandidates(amt: BigDecimal): Seq[String] = {
    val dot = amt.bigDecimal.toPlainString // e.g. "170.64"
    Seq(dot, dot.replace('.', ',')).distinct
  }

  private def dateCandidates(d: LocalDate): Seq[String] = {
    val y = d.getYear
    val yy = f"${y % 100}%02d"
    val m = f"${d.getMonthValue}%02d"
    val dd = f"${d.getDayOfMonth}%02d"
    val mNo = d.getMonthValue.toString
    val dNo = d.getDayOfMonth.toString
    Seq(
      d.toString, // ISO 2026-06-26
      s"$dd/$m/$y",
      s"$dNo/$mNo/$y",
      s"$dd/$m/$yy",
      s"$dd-$m-$y",
      s"$dd.$m.$y",
      s"$dd-$m-$yy",
      s"$dd.$m.$yy"
    ).distinct
  }

  private def bucketLabel(o: FieldOutcome): String =
    if (o.status == Match) "MATCH" else if (o.recoverable) "RECOVERABLE (in raw)" else "ABSENT (not in raw)"

  private def printReport(labeledCount: Int, acc: Acc): Unit = {
    def pct(n: Int, d: Int): String = if (d == 0) "n/a" else f"${100.0 * n / d}%.1f%%"

    println("=" * 78)
    println("PARSER ACCURACY REPORT (receipts confirmed into expenses)")
    println("=" * 78)
    println(s"Labeled receipts (expense_id set): $labeledCount")
    println(s"  skipped - no AnalyzeExpense cache: ${acc.noCache}")
    println(s"  skipped - expense row missing:     ${acc.noExpense}")
    println(s"  skipped - parser returned Left:    ${acc.parseFailed}")
    println(s"  sanity - recomputed != stored amount on receipt row: ${acc.storedReproMismatch} (expect 0)")
    println()

    def fieldBlock(name: String, s: FieldStats): Unit = {
      println(s"--- $name ---")
      println(s"  scored:                  ${s.scored}")
      println(s"  matched:                 ${s.matched}  (${pct(s.matched, s.scored)})")
      println(s"  wrong:                   ${s.wrong}  (${pct(s.wrong, s.scored)})")
      println(s"    mismatch, RECOVERABLE: ${s.mismatchRecoverable}")
      println(s"    mismatch, ABSENT:      ${s.mismatchAbsent}")
      println(s"    missed,   RECOVERABLE: ${s.missedRecoverable}")
      println(s"    missed,   ABSENT:      ${s.missedAbsent}")
      println(s"  => parser-fixable (recoverable):            ${s.recoverable}")
      println(s"  => Textract-missed/user-override (absent):  ${s.absent}")
      println()
    }

    fieldBlock("AMOUNT", acc.amount)
    fieldBlock("DATE", acc.date)

    println("--- AMOUNT mismatches/misses ---")
    acc.amountExamples.foreach(e => println(s"  $e"))
    println()
    println("--- DATE mismatches/misses ---")
    acc.dateExamples.foreach(e => println(s"  $e"))
    println("=" * 78)
  }
}
