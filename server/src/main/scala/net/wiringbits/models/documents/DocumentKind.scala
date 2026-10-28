package net.wiringbits.models.documents

enum DocumentKind(val entryName: String) {
  case Receipt extends DocumentKind("receipt")
  case Invoice extends DocumentKind("invoice")
  case BankStatementPdf extends DocumentKind("bank_statement_pdf")
}

object DocumentKind {
  def withNameOption(name: String): Option[DocumentKind] =
    values.find(_.entryName == name)
}
