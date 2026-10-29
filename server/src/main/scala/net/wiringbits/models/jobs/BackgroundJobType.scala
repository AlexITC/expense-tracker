package net.wiringbits.models.jobs

import net.wiringbits.models.DbEnum

/** `entryName` (the case name) is the value stored in the `background_jobs.type` column.
  *
  * NOTE: Updating this model can cause tasks to fail, for example, if SendEmail is removed while there are pending
  * SendEmail tasks stored at the database.
  */
enum BackgroundJobType extends DbEnum {
  case SendEmail
  case ProcessDocument
  case PollBankStatement
  case ParseBankStatement
  case PollReceipt
  case ParseReceipt
  case ExtractBankStatementDraft
  case ResolveStatementSuggestions
}

object BackgroundJobType extends DbEnum.Companion[BackgroundJobType]
