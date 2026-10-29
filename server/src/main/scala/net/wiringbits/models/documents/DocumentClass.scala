package net.wiringbits.models.documents

import net.wiringbits.models.DbEnum

/** What kind of document an upload is, as told by the AI document classifier. `label` is for user messages. `entryName`
  * (the case name) is the value stored in the `document.ai_classification` column.
  */
enum DocumentClass(val label: String) extends DbEnum {

  /** The label with its indefinite article, e.g. "an invoice". */
  def withArticle: String = s"${if (label.headOption.exists("aeiou".contains(_))) "an" else "a"} $label"

  case BankStatement extends DocumentClass("bank statement")
  case PurchaseTicket extends DocumentClass("purchase ticket")
  case BankVoucher extends DocumentClass("bank voucher")
  case Invoice extends DocumentClass("invoice")
  case Other extends DocumentClass("other document")
}

object DocumentClass extends DbEnum.Companion[DocumentClass]
