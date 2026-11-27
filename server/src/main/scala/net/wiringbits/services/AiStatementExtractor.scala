package net.wiringbits.services

import net.wiringbits.api.models.bankstatementtemplates.{ExtractionSource, ProductType}
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
      residual: Option[BigDecimal],
      // Which ladder stage produced this result, stamped by StatementExtractionService. None until the ladder tags
      // it — parseAndVerify on its own leaves it unset.
      source: Option[ExtractionSource] = None,
      // True only when completeness can't be checked at all: no opening/closing balance pair and no usable
      // running-balance chain. A chain break or a balance mismatch is a real discrepancy, not unverifiable.
      unverifiable: Boolean
  )

  /** Outcome of re-checking a draft's arithmetic. `unverifiable` has the same meaning as on [[ExtractionResult]]. */
  final case class Reconciliation(reconciled: Boolean, unverifiable: Boolean, discrepancy: Option[String])

  /** An unverifiable outcome is acceptable only when there is at least one transaction. */
  def isTerminalUnverifiable(unverifiable: Boolean, transactionCount: Int): Boolean =
    unverifiable && transactionCount > 0

  private final case class Verification(
      reconciled: Boolean,
      unverifiable: Boolean,
      discrepancy: Option[String],
      residual: Option[BigDecimal]
  )

  /** Pick the best of several extraction attempts: any that reconciles wins; otherwise one that found balances (a real
    * discrepancy) beats an unverifiable one, then the smallest numeric gap to the balance; an attempt with no
    * computable gap is considered worst.
    */
  def bestOf(results: List[ExtractionResult]): Option[ExtractionResult] =
    if (results.isEmpty) None
    else
      Some(
        results
          .find(_.reconciled)
          .getOrElse {
            val anchored = results.filterNot(_.unverifiable)
            val pool = if (anchored.nonEmpty) anchored else results
            pool.minBy(_.residual.getOrElse(BigDecimal(Long.MaxValue)))
          }
      )

  def parseAndVerify(rawJson: String, model: String): Either[String, ExtractionResult] =
    Try(Json.parse(extractJsonObject(rawJson))).toEither.left
      .map(e => s"Model did not return valid JSON: ${e.getMessage}")
      .flatMap { json =>
        val rows = (json \ "transactions").asOpt[List[JsValue]].getOrElse(Nil)
        rows.zipWithIndex
          .foldRight[Either[String, List[Txn]]](Right(Nil)) { case ((t, i), acc) =>
            parseTxn(t, i + 1).flatMap(txn => acc.map(txn :: _))
          }
          .flatMap(txns => buildResult(json, txns, model))
      }

  private def parseTxn(t: JsValue, n: Int): Either[String, Txn] = {
    def malformed(field: String) = s"Transaction $n is malformed: missing or invalid $field"
    def required[A: Reads](field: String): Either[String, A] =
      (t \ field).asOpt[A].toRight(malformed(field))
    def optional[A: Reads](field: String): Either[String, Option[A]] =
      (t \ field).validateOpt[A].asOpt.toRight(malformed(field))
    for {
      date <- required[LocalDate]("date")
      amount <- required[BigDecimal]("amount")
      description <- required[String]("description")
      runningBalance <- optional[BigDecimal]("runningBalance")
      txnType <- optional[String]("type")
    } yield Txn(date, amount, cleanDescription(description), runningBalance, txnType)
  }

  private def buildResult(json: JsValue, txns: List[Txn], model: String): Either[String, ExtractionResult] =
    if (txns.isEmpty) Left("The model extracted no transactions")
    else {
      val m = json \ "metadata"
      val (periodStart, periodEnd) = resolvePeriod(
        start = (m \ "periodStart").asOpt[LocalDate],
        end = (m \ "periodEnd").asOpt[LocalDate],
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
      val v = verify(meta, txns)
      Right(
        ExtractionResult(
          v.reconciled,
          v.discrepancy,
          model,
          txns,
          meta,
          v.residual,
          unverifiable = v.unverifiable
        )
      )
    }

  /** Map a draft's stored product type to the enum; an unknown value maps to None. */
  def parseProductType(s: String): Option[ProductType] = ProductType.values.find(_.entryName == s.trim)

  /** Re-check reconciliation for an (edited) draft, from the balances and each row's amount + optional running balance.
    * Reuses the same rule as extraction so a user's edits are held to the same arithmetic gate.
    */
  def reconcile(
      productType: Option[ProductType],
      openingBalance: Option[BigDecimal],
      closingBalance: Option[BigDecimal],
      rows: List[(BigDecimal, Option[BigDecimal])]
  ): Reconciliation = {
    val txns = rows.map { case (amount, runningBalance) => Txn(LocalDate.MIN, amount, "", runningBalance, None) }
    val v =
      verify(
        Meta(
          bankName = None,
          productType = productType,
          currency = None,
          accountLast4 = None,
          periodStart = None,
          periodEnd = None,
          openingBalance = openingBalance,
          closingBalance = closingBalance
        ),
        txns
      )
    Reconciliation(reconciled = v.reconciled, unverifiable = v.unverifiable, discrepancy = v.discrepancy)
  }

  private val OppositeSignForDebit =
    "The amounts have the opposite sign for a debit account: charges must be negative and deposits positive."

  private def verify(meta: Meta, txns: List[Txn]): Verification = {
    val sumAll = txns.map(_.amount).foldLeft(BigDecimal(0))(_ + _)
    // A debit account has a known convention (charges negative, balance = opening + sum); credit or unknown accepts
    // either sign.
    val isDebit = meta.productType.contains(ProductType.Debit)

    val chain: Option[Boolean] = meta.openingBalance
      .filter(_ => txns.forall(_.runningBalance.isDefined))
      .map(o => chainHolds(o, txns, sign = 1) || (!isDebit && chainHolds(o, txns, sign = -1)))
    lazy val chainInverted: Boolean =
      isDebit && meta.openingBalance.exists(o => chainHolds(o, txns, sign = -1))

    if (txns.isEmpty)
      Verification(
        reconciled = false,
        unverifiable = false,
        Some("There are no transactions, so the statement can't be reconciled."),
        None
      )
    else
      chain match {
        case Some(true) =>
          (meta.closingBalance, txns.last.runningBalance) match {
            case (Some(c), Some(last)) =>
              // Either balance-sign convention, as in the chain itself.
              val gap = ((last - c).abs).min((last + c).abs)
              if (gap < Tolerance) Verification(reconciled = true, unverifiable = false, None, Some(BigDecimal(0)))
              else
                Verification(
                  reconciled = false,
                  unverifiable = false,
                  Some(s"The running balance ends at $last but the closing balance is $c; rows are likely missing."),
                  Some(gap)
                )
            case _ => Verification(reconciled = true, unverifiable = false, None, Some(BigDecimal(0)))
          }
        case Some(false) if chainInverted =>
          Verification(reconciled = false, unverifiable = false, Some(OppositeSignForDebit), None)
        case Some(false) =>
          Verification(
            reconciled = false,
            unverifiable = false,
            Some(
              s"The running balance does not add up across the ${txns.size} rows; a row is likely missing or misread."
            ),
            None
          )
        case None =>
          (meta.openingBalance, meta.closingBalance) match {
            case (Some(o), Some(c)) =>
              // Debit: c-o only. Credit/unknown: either balance-sign convention (c-o or o-c).
              val debitGap = sumAll - (c - o)
              val oppositeGap = sumAll - (o - c)
              val missing = if (isDebit) debitGap else List(debitGap, oppositeGap).minBy(_.abs)
              if (missing.abs < Tolerance)
                Verification(reconciled = true, unverifiable = false, None, Some(missing.abs))
              else if (isDebit && oppositeGap.abs < Tolerance)
                Verification(reconciled = false, unverifiable = false, Some(OppositeSignForDebit), Some(missing.abs))
              else
                Verification(
                  reconciled = false,
                  unverifiable = false,
                  Some(
                    s"Transactions sum to $sumAll but the balance changes by ±${(c - o).abs} — off by ${missing.abs}."
                  ),
                  Some(missing.abs)
                )
            case _ =>
              Verification(
                reconciled = false,
                unverifiable = true,
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
