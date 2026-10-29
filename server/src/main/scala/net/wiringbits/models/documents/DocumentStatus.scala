package net.wiringbits.models.documents

import net.wiringbits.models.DbEnum

/** `entryName` (the case name) is the value stored in status columns (e.g. `receipt.status`). The API representation
  * ([[net.wiringbits.api.models.documents.DocumentStatus]]) is a separate type the UI consumes; Chimney maps between
  * the two by case name.
  */
enum DocumentStatus extends DbEnum {
  case Ingested
  case Processing
  case Analyzed
  case Parsed
  case ParseFailed
  case Unsupported
}

object DocumentStatus {
  def withNameInsensitiveOption(name: String): Option[DocumentStatus] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
