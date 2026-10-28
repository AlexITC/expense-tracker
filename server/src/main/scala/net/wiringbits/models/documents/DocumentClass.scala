package net.wiringbits.models.documents

/** What kind of document an upload is, as told by the AI document classifier. `label` is for user messages. */
enum DocumentClass(val entryName: String, val label: String) {

  /** The label with its indefinite article, e.g. "an invoice". */
  def withArticle: String = s"${if (label.headOption.exists("aeiou".contains(_))) "an" else "a"} $label"

  case BankStatement extends DocumentClass("BANKSTATEMENT", "bank statement")
  case PurchaseTicket extends DocumentClass("PURCHASETICKET", "purchase ticket")
  case BankVoucher extends DocumentClass("BANKVOUCHER", "bank voucher")
  case Invoice extends DocumentClass("INVOICE", "invoice")
  case Other extends DocumentClass("OTHER", "other document")
}

object DocumentClass {
  def withNameInsensitiveOption(name: String): Option[DocumentClass] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
