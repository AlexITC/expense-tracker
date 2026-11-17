package net.wiringbits.api.utils

// Bounds on a bank statement draft, shared so the client can enforce what the server does.
object BankStatementDraftLimits {
  val MaxTransactions: Int = 250
  val MaxDescriptionLength: Int = 255
  val MaxTxnTypeLength: Int = 20
  val MaxBankNameLength: Int = 100
  val MaxProductTypeLength: Int = 20
  val MaxCurrencyLength: Int = 3
  val MaxAccountLast4Length: Int = 4
  val MaxCsvBytes: Int = 2 * 1024 * 1024
  val MaxFilenameLength: Int = 255

  // Statement amounts are stored as NUMERIC(12,2); anything wider would overflow or be rounded on confirm.
  val MaxAmountIntegerDigits: Int = 10
  val MaxAmountFractionDigits: Int = 2

  /** A plain decimal (no exponent, no grouping), regardless of width. */
  val DecimalPattern: scala.util.matching.Regex = "[+-]?\\d+(\\.\\d+)?".r

  /** A plain decimal that fits the amount columns. */
  val BoundedDecimalPattern: scala.util.matching.Regex =
    s"[+-]?\\d{1,$MaxAmountIntegerDigits}(\\.\\d{1,$MaxAmountFractionDigits})?".r
}
