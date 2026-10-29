package net.wiringbits.models.jobs

import net.wiringbits.models.DbEnum

/** `entryName` (the case name) is the value stored in the `background_jobs.status` column. */
enum BackgroundJobStatus extends DbEnum {
  case Success
  case Pending
  case Failed
  case Running
}

object BackgroundJobStatus extends DbEnum.Companion[BackgroundJobStatus]
