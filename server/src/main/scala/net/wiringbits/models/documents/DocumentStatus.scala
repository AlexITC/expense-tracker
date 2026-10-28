package net.wiringbits.models.documents

enum DocumentStatus(val entryName: String) {
  case Ingested extends DocumentStatus("INGESTED")
  case Processing extends DocumentStatus("PROCESSING")
  case Analyzed extends DocumentStatus("ANALYZED")
  case Parsed extends DocumentStatus("PARSED")
  case ParseFailed extends DocumentStatus("PARSEFAILED")
  case Unsupported extends DocumentStatus("UNSUPPORTED")
}

object DocumentStatus {
  def withNameInsensitiveOption(name: String): Option[DocumentStatus] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
