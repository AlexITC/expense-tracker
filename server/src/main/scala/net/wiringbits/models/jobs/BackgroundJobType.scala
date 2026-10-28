package net.wiringbits.models.jobs

/** NOTE: Updating this model can cause tasks to fail, for example, if SendEmail is removed while there are pending
  * SendEmail tasks stored at the database
  */
enum BackgroundJobType(val entryName: String) {
  case SendEmail extends BackgroundJobType("SENDEMAIL")
  case ProcessDocument extends BackgroundJobType("PROCESSDOCUMENT")
  case PollBankStatement extends BackgroundJobType("POLLBANKSTATEMENT")
  case ParseBankStatement extends BackgroundJobType("PARSEBANKSTATEMENT")
  case PollReceipt extends BackgroundJobType("POLLRECEIPT")
  case ParseReceipt extends BackgroundJobType("PARSERECEIPT")
  case ExtractBankStatementDraft extends BackgroundJobType("EXTRACTBANKSTATEMENTDRAFT")
  case ResolveStatementSuggestions extends BackgroundJobType("RESOLVESTATEMENTSUGGESTIONS")
}

object BackgroundJobType {
  def withNameInsensitiveOption(name: String): Option[BackgroundJobType] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
