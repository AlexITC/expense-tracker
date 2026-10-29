package net.wiringbits.models.documents

/** `entryName` is the value stored in status columns (e.g. `receipt.status`). The API representation
  * ([[net.wiringbits.api.models.documents.DocumentStatus]]) is a separate type the UI consumes; Chimney maps between
  * the two by case name.
  */
enum DocumentStatus(val entryName: String) {
  case Ingested extends DocumentStatus("Ingested")
  case Processing extends DocumentStatus("Processing")
  case Analyzed extends DocumentStatus("Analyzed")
  case Parsed extends DocumentStatus("Parsed")
  case ParseFailed extends DocumentStatus("ParseFailed")
  case Unsupported extends DocumentStatus("Unsupported")
}

object DocumentStatus {
  def withNameInsensitiveOption(name: String): Option[DocumentStatus] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
