package net.wiringbits.api.utils

// Bounds on a bank statement draft, shared so the client can enforce what the server does.
object BankStatementDraftLimits {
  val MaxTransactions: Int = 250
  val MaxDescriptionLength: Int = 255
  val MaxTxnTypeLength: Int = 20
  val MaxCsvBytes: Int = 2 * 1024 * 1024
  val MaxFilenameLength: Int = 255
}
