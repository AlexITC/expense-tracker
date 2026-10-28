package net.wiringbits.models.documents

import net.wiringbits.api.models.documents.DocumentStatus as ApiDocumentStatus

/** `entryName` is the value stored in status columns (e.g. `receipt.status`). It differs from the API representation
  * ([[ApiDocumentStatus]]), which the UI consumes — [[toApi]] maps between the two.
  */
enum DocumentStatus(val entryName: String) {
  case Ingested extends DocumentStatus("Ingested")
  case Processing extends DocumentStatus("Processing")
  case Analyzed extends DocumentStatus("Analyzed")
  case Parsed extends DocumentStatus("Parsed")
  case ParseFailed extends DocumentStatus("ParseFailed")
  case Unsupported extends DocumentStatus("Unsupported")

  def toApi: ApiDocumentStatus = this match {
    case Ingested => ApiDocumentStatus.Ingested
    case Processing => ApiDocumentStatus.Processing
    case Analyzed => ApiDocumentStatus.Analyzed
    case Parsed => ApiDocumentStatus.Parsed
    case ParseFailed => ApiDocumentStatus.ParseFailed
    case Unsupported => ApiDocumentStatus.Unsupported
  }
}

object DocumentStatus {
  def withNameInsensitiveOption(name: String): Option[DocumentStatus] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
