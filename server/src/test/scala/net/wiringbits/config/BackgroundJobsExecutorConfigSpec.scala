package net.wiringbits.config

import net.wiringbits.config.BackgroundJobsExecutorConfig.TypeSettings
import net.wiringbits.models.jobs.BackgroundJobType
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

import scala.concurrent.duration.*

class BackgroundJobsExecutorConfigSpec extends AnyWordSpec with Matchers {

  "defaultPerType" should {
    "cover every job type" in {
      BackgroundJobType.values.filterNot(BackgroundJobsExecutorConfig.defaultPerType.contains) must be(empty)
    }
  }

  "constructor" should {
    "fail when a job type has no settings, naming the type" in {
      val perType = BackgroundJobsExecutorConfig.defaultPerType - BackgroundJobType.ParseReceipt
      val error = intercept[IllegalStateException](BackgroundJobsExecutorConfig(1.minute, perType))
      error.getMessage must include("ParseReceipt")
    }

    "reject non-positive parallelism, naming the type" in {
      Seq(0, -1).foreach { parallelism =>
        val perType = BackgroundJobsExecutorConfig.defaultPerType.updated(
          BackgroundJobType.SendEmail,
          TypeSettings(parallelism = parallelism, timeout = 1.minute, pollInterval = 5.seconds)
        )
        val error = intercept[IllegalStateException](BackgroundJobsExecutorConfig(1.minute, perType))
        error.getMessage must include("parallelism")
        error.getMessage must include("SendEmail")
      }
    }

    "accept a parallelism of 1" in {
      val settings = TypeSettings(parallelism = 1, timeout = 1.minute, pollInterval = 5.seconds)
      val perType = BackgroundJobsExecutorConfig.defaultPerType.updated(BackgroundJobType.SendEmail, settings)
      BackgroundJobsExecutorConfig(1.minute, perType).settingsFor(BackgroundJobType.SendEmail) must be(settings)
    }
  }

  "settingsFor" should {
    "return the settings for the given type" in {
      val config = BackgroundJobsExecutorConfig(1.minute, BackgroundJobsExecutorConfig.defaultPerType)
      config.settingsFor(BackgroundJobType.SendEmail) must be(
        TypeSettings(parallelism = 10, timeout = 1.minute, pollInterval = 5.seconds)
      )
    }
  }

  "apply" should {
    "read the interval and use the default per-type settings" in {
      val config = BackgroundJobsExecutorConfig(Configuration("interval" -> "1 minute"))
      config.interval must be(1.minute)
      config.perType must be(BackgroundJobsExecutorConfig.defaultPerType)
    }
  }
}
