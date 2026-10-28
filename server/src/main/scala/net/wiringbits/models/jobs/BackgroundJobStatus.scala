package net.wiringbits.models.jobs

/** `entryName` is the value stored in the `background_jobs.status` column. */
enum BackgroundJobStatus(val entryName: String) {
  case Success extends BackgroundJobStatus("Success")
  case Pending extends BackgroundJobStatus("Pending")
  case Failed extends BackgroundJobStatus("Failed")
  case Running extends BackgroundJobStatus("Running")
}

object BackgroundJobStatus {
  def withNameInsensitiveOption(name: String): Option[BackgroundJobStatus] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
