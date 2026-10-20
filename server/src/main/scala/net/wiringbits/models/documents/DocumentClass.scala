package net.wiringbits.models.documents

import enumeratum.EnumEntry.Uppercase
import enumeratum.{Enum, EnumEntry}

/** What kind of document an upload is, as told by the AI document classifier. `label` is for user messages. */
sealed abstract class DocumentClass(val label: String) extends EnumEntry with Uppercase {

  /** The label with its indefinite article, e.g. "an invoice". */
  def withArticle: String = s"${if (label.headOption.exists("aeiou".contains(_))) "an" else "a"} $label"
}

object DocumentClass extends Enum[DocumentClass] {
  case object BankStatement extends DocumentClass("bank statement")
  case object PurchaseTicket extends DocumentClass("purchase ticket")
  case object BankVoucher extends DocumentClass("bank voucher")
  case object Invoice extends DocumentClass("invoice")
  case object Other extends DocumentClass("other document")

  val values = findValues
}
