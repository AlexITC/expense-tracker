package net.wiringbits.models.documents

import net.wiringbits.models.DbEnum

/** `entryName` (the case name) is the value stored in the `document.kind` column. */
enum DocumentKind extends DbEnum {
  case Receipt
  case Invoice
  case BankStatementPdf
}

object DocumentKind extends DbEnum.Companion[DocumentKind]
