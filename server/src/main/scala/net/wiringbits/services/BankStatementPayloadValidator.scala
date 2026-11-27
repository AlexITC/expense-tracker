package net.wiringbits.services

import net.wiringbits.api.models.bankstatementtemplates.ProductType
import net.wiringbits.api.utils.BankStatementDraftLimits

import scala.jdk.CollectionConverters.*

/** Validates the values of a bank statement payload: lengths, enums, currency code, and amount precision. A `None` is
  * always valid here; whether a field is required is decided at confirm.
  *
  * Pure and free of tapir/`BankStatementDraft` so [[AiStatementExtractor]] can keep running in an isolated JVM.
  */
object BankStatementPayloadValidator {

  final case class Header(
      bankName: Option[String],
      productType: Option[String],
      currency: Option[String],
      accountLast4: Option[String],
      openingBalance: Option[BigDecimal],
      closingBalance: Option[BigDecimal]
  )

  final case class Row(
      description: String,
      amount: BigDecimal,
      txnType: Option[String],
      runningBalance: Option[BigDecimal]
  )

  private val ProductTypes: Seq[String] = ProductType.values.map(_.entryName)
  private val CurrencyCodes: Set[String] =
    java.util.Currency.getAvailableCurrencies.asScala.map(_.getCurrencyCode).toSet
  private val MaxAmountExclusive: BigDecimal = BigDecimal(10).pow(BankStatementDraftLimits.MaxAmountIntegerDigits)

  def validate(h: Header, rows: List[Row]): Either[String, Unit] =
    rows.zipWithIndex.foldLeft(validateHeader(h)) { case (acc, (r, i)) => acc.flatMap(_ => validateRow(i + 1, r)) }

  def validateHeader(h: Header): Either[String, Unit] =
    for {
      _ <- maxLength("Bank name", h.bankName, BankStatementDraftLimits.MaxBankNameLength)
      _ <- maxLength("Product type", h.productType, BankStatementDraftLimits.MaxProductTypeLength)
      _ <- maxLength("Currency", h.currency, BankStatementDraftLimits.MaxCurrencyLength)
      _ <- maxLength("Account last 4", h.accountLast4, BankStatementDraftLimits.MaxAccountLast4Length)
      _ <- oneOf("Product type", h.productType, ProductTypes)
      _ <- h.currency.filterNot(isCurrencyCode).map(c => s"Currency $c is not an ISO 4217 code").toLeft(())
      _ <- amount("Opening balance", h.openingBalance)
      _ <- amount("Closing balance", h.closingBalance)
    } yield ()

  /** `rowNumber` is 1-based. */
  def validateRow(rowNumber: Int, r: Row): Either[String, Unit] = {
    val label = s"Row $rowNumber:"
    for {
      _ <- maxLength(s"$label description", Some(r.description), BankStatementDraftLimits.MaxDescriptionLength)
      _ <- maxLength(s"$label type", r.txnType, BankStatementDraftLimits.MaxTxnTypeLength)
      _ <- oneOf(s"$label type", r.txnType, BankStatementDraftLimits.TxnTypes)
      _ <- amount(s"$label amount", Some(r.amount))
      _ <- amount(s"$label running balance", r.runningBalance)
    } yield ()
  }

  private def maxLength(label: String, value: Option[String], max: Int): Either[String, Unit] =
    if (value.exists(_.length > max)) Left(s"$label is longer than $max characters") else Right(())

  private def oneOf(label: String, value: Option[String], allowed: Seq[String]): Either[String, Unit] =
    value
      .filterNot(allowed.contains)
      .map(v => s"$label $v is not one of ${allowed.mkString(", ")}")
      .toLeft(())

  private def isCurrencyCode(c: String): Boolean =
    c.length == 3 && c.forall(ch => ch >= 'A' && ch <= 'Z') && CurrencyCodes.contains(c)

  private def amount(label: String, value: Option[BigDecimal]): Either[String, Unit] =
    value match {
      case Some(v) if v.bigDecimal.stripTrailingZeros.scale > BankStatementDraftLimits.MaxAmountFractionDigits =>
        Left(s"$label $v has more than ${BankStatementDraftLimits.MaxAmountFractionDigits} decimal places")
      case Some(v) if v.abs >= MaxAmountExclusive =>
        Left(s"$label $v has more than ${BankStatementDraftLimits.MaxAmountIntegerDigits} integer digits")
      case _ => Right(())
    }
}
