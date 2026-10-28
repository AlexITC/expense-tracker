package net.wiringbits.models.jobs

/** `entryName` is the value stored in the `background_jobs.type` column.
  *
  * NOTE: Updating this model can cause tasks to fail, for example, if SendEmail is removed while there are pending
  * SendEmail tasks stored at the database.
  */
enum BackgroundJobType(val entryName: String) {
  case SendEmail extends BackgroundJobType("SendEmail")
  case ProcessDocument extends BackgroundJobType("ProcessDocument")
  case PollBankStatement extends BackgroundJobType("PollBankStatement")
  case ParseBankStatement extends BackgroundJobType("ParseBankStatement")
  case PollReceipt extends BackgroundJobType("PollReceipt")
  case ParseReceipt extends BackgroundJobType("ParseReceipt")
  case ExtractBankStatementDraft extends BackgroundJobType("ExtractBankStatementDraft")
  case ResolveStatementSuggestions extends BackgroundJobType("ResolveStatementSuggestions")
}

object BackgroundJobType {
  def withNameInsensitiveOption(name: String): Option[BackgroundJobType] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
