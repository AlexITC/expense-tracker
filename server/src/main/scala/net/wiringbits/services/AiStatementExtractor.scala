package net.wiringbits.services

import net.wiringbits.api.models.bankstatementtemplates.ProductType
import play.api.libs.json.*

import java.time.{LocalDate, YearMonth}
import scala.util.Try

/** Parses the model's raw JSON into transactions + metadata and verifies them against the statement's own arithmetic: a
  * per-row running-balance chain when present, otherwise opening ± sum = closing (sign-agnostic, so a model that
  * reports a credit balance as positive-held or negative-owed both reconcile). Pure and deterministic.
  *
  * Returns plain internal types (no tapir Schema) so this can be unit-tested and script-run in an isolated JVM; the
  * action maps the result to the shared API response (whose schema only initialises safely inside the booted app).
  */
object AiStatementExtractor {
  private val Tolerance = BigDecimal("0.01")

  final case class Txn(
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      runningBalance: Option[BigDecimal],
      txnType: Option[String]
  )
  final case class Meta(
      bankName: Option[String],
      productType: Option[ProductType],
      currency: Option[String],
      accountLast4: Option[String],
      periodStart: Option[LocalDate],
      periodEnd: Option[LocalDate],
      openingBalance: Option[BigDecimal],
      closingBalance: Option[BigDecimal]
  )
  final case class ExtractionResult(
      reconciled: Boolean,
      discrepancy: Option[String],
      model: String,
      transactions: List[Txn],
      metadata: Meta,
      // How far the transactions are from the balance, when a numeric gap is computable (the net-check path). None when
      // it isn't (running-balance chain, or no balances at all). Used to pick the best of several attempts.
      residual: Option[BigDecimal] = None,
      // Which ladder stage produced this result, stamped by StatementExtractionService (e.g. "PDF text", "BDA
      // markdown"). None until the ladder tags it — parseAndVerify on its own leaves it unset.
      source: Option[String] = None
  )

  /** Pick the best of several extraction attempts: any that reconciles wins; otherwise the one with the smallest
    * numeric gap to the balance; an attempt with no computable gap is considered worst.
    */
  def bestOf(results: List[ExtractionResult]): Option[ExtractionResult] =
    if (results.isEmpty) None
    else
      Some(
        results
          .find(_.reconciled)
          .getOrElse(results.minBy(_.residual.getOrElse(BigDecimal(Long.MaxValue))))
      )

  def parseAndVerify(rawJson: String, model: String): Either[String, ExtractionResult] =
    Try(Json.parse(extractJsonObject(rawJson))).toEither.left
      .map(e => s"Model did not return valid JSON: ${e.getMessage}")
      .flatMap { json =>
        val txns = (json \ "transactions").asOpt[List[JsValue]].getOrElse(Nil).flatMap { t =>
          for {
            date <- (t \ "date").asOpt[LocalDate]
            amount <- (t \ "amount").asOpt[BigDecimal]
            description <- (t \ "description").asOpt[String]
          } yield Txn(
            date,
            amount,
            cleanDescription(description),
            (t \ "runningBalance").asOpt[BigDecimal],
            (t \ "type").asOpt[String]
          )
        }
        if (txns.isEmpty) Left("The model extracted no transactions")
        else {
          val m = json \ "metadata"
          val (periodStart, periodEnd) = resolvePeriod(
            (m \ "periodStart").asOpt[LocalDate],
            (m \ "periodEnd").asOpt[LocalDate],
            (m \ "periodMonth").asOpt[String]
          )
          val meta = Meta(
            bankName = (m \ "bankName").asOpt[String],
            productType = (m \ "productType").asOpt[ProductType],
            currency = (m \ "currency").asOpt[String],
            accountLast4 = (m \ "accountLast4").asOpt[String],
            periodStart = periodStart,
            periodEnd = periodEnd,
            openingBalance = (m \ "openingBalance").asOpt[BigDecimal],
            closingBalance = (m \ "closingBalance").asOpt[BigDecimal]
          )
          val (reconciled, discrepancy, residual) = verify(meta, txns)
          Right(ExtractionResult(reconciled, discrepancy, model, txns, meta, residual))
        }
      }

  /** Re-check reconciliation for an (edited) draft, from the balances and each row's amount + optional running balance.
    * Reuses the same rule as extraction so a user's edits are held to the same arithmetic gate.
    */
  def reconcile(
      openingBalance: Option[BigDecimal],
      closingBalance: Option[BigDecimal],
      rows: List[(BigDecimal, Option[BigDecimal])]
  ): (Boolean, Option[String]) = {
    val txns = rows.map { case (amount, runningBalance) => Txn(LocalDate.MIN, amount, "", runningBalance, None) }
    val (reconciled, discrepancy, _) =
      verify(Meta(None, None, None, None, None, None, openingBalance, closingBalance), txns)
    (reconciled, discrepancy)
  }

  private def verify(meta: Meta, txns: List[Txn]): (Boolean, Option[String], Option[BigDecimal]) = {
    val sumAll = txns.map(_.amount).foldLeft(BigDecimal(0))(_ + _)

    val chain: Option[Boolean] = meta.openingBalance
      .filter(_ => txns.forall(_.runningBalance.isDefined))
      .map(o => chainHolds(o, txns, sign = 1) || chainHolds(o, txns, sign = -1))

    chain match {
      case Some(true) => (true, None, Some(BigDecimal(0)))
      case Some(false) =>
        (
          false,
          Some(
            s"The running balance does not add up across the ${txns.size} rows; a row is likely missing or misread."
          ),
          None
        )
      case None =>
        (meta.openingBalance, meta.closingBalance) match {
          case (Some(o), Some(c)) =>
            // Accept either balance-sign convention (debit: c-o; credit/owed: o-c).
            val missing = List(sumAll - (c - o), sumAll - (o - c)).minBy(_.abs)
            if (missing.abs < Tolerance) (true, None, Some(missing.abs))
            else
              (
                false,
                Some(
                  s"Transactions sum to $sumAll but the balance changes by ±${(c - o).abs} — off by ${missing.abs}."
                ),
                Some(missing.abs)
              )
          case _ =>
            (
              false,
              Some("No running balance or opening/closing balance was found, so completeness can't be verified."),
              None
            )
        }
    }
  }

  private def chainHolds(opening: BigDecimal, txns: List[Txn], sign: Int): Boolean =
    txns
      .foldLeft[Option[BigDecimal]](Some(opening)) { (prevOpt, t) =>
        prevOpt.flatMap { prev =>
          val expected = prev + sign * t.amount
          t.runningBalance.filter(b => (b - expected).abs < Tolerance)
        }
      }
      .isDefined

  /** Prefer an explicit printed date range. When the statement states only a billing month (e.g. "04-2026"), the model
    * returns it as periodMonth="YYYY-MM" and we cover the whole month deterministically.
    */
  private def resolvePeriod(
      start: Option[LocalDate],
      end: Option[LocalDate],
      month: Option[String]
  ): (Option[LocalDate], Option[LocalDate]) =
    if (start.isDefined || end.isDefined) (start, end)
    else
      month.flatMap(s => Try(YearMonth.parse(s)).toOption) match {
        case Some(ym) => (Some(ym.atDay(1)), Some(ym.atEndOfMonth))
        case None => (None, None)
      }

  /** Native PDF text is column-aligned, so descriptions arrive with long runs of spaces (and continuation lines joined
    * with newlines). Collapse each line's internal whitespace to single spaces and drop blank lines, keeping the line
    * breaks between a transaction's continuation lines.
    */
  private def cleanDescription(description: String): String =
    description.split("\n").iterator.map(_.replaceAll("\\s+", " ").trim).filter(_.nonEmpty).mkString("\n")

  private def extractJsonObject(text: String): String = {
    val t = text.trim
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start >= 0 && end > start) t.substring(start, end + 1) else t
  }
}
