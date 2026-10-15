package net.wiringbits.models.documents

import enumeratum.{Enum, EnumEntry}

sealed abstract class DocumentKind(override val entryName: String) extends EnumEntry

object DocumentKind extends Enum[DocumentKind] {
  case object Receipt extends DocumentKind("receipt")
  case object Invoice extends DocumentKind("invoice")
  case object BankStatementPdf extends DocumentKind("bank_statement_pdf")

  val values: IndexedSeq[DocumentKind] = findValues
}
