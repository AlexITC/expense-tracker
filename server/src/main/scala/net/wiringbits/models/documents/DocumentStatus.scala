package net.wiringbits.models.documents

import enumeratum.EnumEntry.Uppercase
import enumeratum.{Enum, EnumEntry}

sealed trait DocumentStatus extends EnumEntry with Uppercase

object DocumentStatus extends Enum[DocumentStatus] {
  case object Ingested extends DocumentStatus
  case object Processing extends DocumentStatus
  case object Analyzed extends DocumentStatus
  case object Parsed extends DocumentStatus
  case object ParseFailed extends DocumentStatus
  case object Unsupported extends DocumentStatus

  val values = findValues
}
