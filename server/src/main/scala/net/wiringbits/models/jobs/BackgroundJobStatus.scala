package net.wiringbits.models.jobs

enum BackgroundJobStatus(val entryName: String) {
  case Success extends BackgroundJobStatus("SUCCESS")
  case Pending extends BackgroundJobStatus("PENDING")
  case Failed extends BackgroundJobStatus("FAILED")
  case Running extends BackgroundJobStatus("RUNNING")
}

object BackgroundJobStatus {
  def withNameInsensitiveOption(name: String): Option[BackgroundJobStatus] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
