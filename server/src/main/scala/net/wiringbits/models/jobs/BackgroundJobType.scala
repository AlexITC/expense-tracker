package net.wiringbits.models.jobs

import enumeratum.EnumEntry.Uppercase
import enumeratum.{Enum, EnumEntry}

sealed trait BackgroundJobType extends EnumEntry with Uppercase

/** NOTE: Updating this model can cause tasks to fail, for example, if SendEmail is removed while there are pending
  * SendEmail tasks stored at the database
  */
object BackgroundJobType extends Enum[BackgroundJobType] {
  case object SendEmail extends BackgroundJobType
  case object ProcessDocument extends BackgroundJobType
  case object PollBankStatement extends BackgroundJobType
  case object ParseBankStatement extends BackgroundJobType
  case object PollReceipt extends BackgroundJobType
  case object ParseReceipt extends BackgroundJobType
  case object ExtractBankStatementDraft extends BackgroundJobType

  val values = findValues
}
