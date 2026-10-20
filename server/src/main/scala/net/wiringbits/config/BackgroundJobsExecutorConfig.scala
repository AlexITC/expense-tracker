package net.wiringbits.config

import net.wiringbits.config.BackgroundJobsExecutorConfig.TypeSettings
import net.wiringbits.models.jobs.BackgroundJobType
import play.api.Configuration

import scala.concurrent.duration.*

case class BackgroundJobsExecutorConfig(interval: FiniteDuration, perType: Map[BackgroundJobType, TypeSettings]) {
  private val missingTypes = BackgroundJobType.values.filterNot(perType.contains)
  if (missingTypes.nonEmpty) {
    throw new IllegalStateException(
      s"Missing background job settings for types: ${missingTypes.mkString(", ")}"
    )
  }

  private val invalidParallelism = BackgroundJobType.values.filter(t => perType(t).parallelism < 1)
  if (invalidParallelism.nonEmpty) {
    throw new IllegalStateException(
      s"Background job parallelism must be >= 1, invalid types: ${invalidParallelism.mkString(", ")}"
    )
  }

  def settingsFor(jobType: BackgroundJobType): TypeSettings = perType(jobType)

  override def toString: String = {
    val perTypeString = BackgroundJobType.values.map(t => s"$t = ${perType(t)}").mkString(", ")
    s"BackgroundJobsExecutorConfig(interval = $interval, perType = [$perTypeString])"
  }
}

object BackgroundJobsExecutorConfig {
  case class TypeSettings(parallelism: Int, timeout: FiniteDuration, pollInterval: FiniteDuration)

  // Parallelism reflects each type's priority plus external limits (Bedrock/JEV rate limits for the
  // document/statement types); timeouts reflect how long each handler can legitimately take. To be tuned later.
  val defaultPerType: Map[BackgroundJobType, TypeSettings] = Map(
    BackgroundJobType.SendEmail -> TypeSettings(parallelism = 10, timeout = 1.minute, pollInterval = 5.seconds),
    BackgroundJobType.PollReceipt -> TypeSettings(parallelism = 5, timeout = 1.minute, pollInterval = 5.seconds),
    BackgroundJobType.ProcessDocument -> TypeSettings(parallelism = 3, timeout = 2.minutes, pollInterval = 5.seconds),
    BackgroundJobType.ParseReceipt -> TypeSettings(parallelism = 3, timeout = 2.minutes, pollInterval = 5.seconds),
    BackgroundJobType.ExtractBankStatementDraft -> TypeSettings(
      parallelism = 2,
      timeout = 6.minutes,
      pollInterval = 5.seconds
    ),
    BackgroundJobType.ResolveStatementSuggestions -> TypeSettings(
      parallelism = 2,
      timeout = 6.minutes,
      pollInterval = 5.seconds
    ),
    BackgroundJobType.PollBankStatement -> TypeSettings(
      parallelism = 1,
      timeout = 30.seconds,
      pollInterval = 5.seconds
    ),
    BackgroundJobType.ParseBankStatement -> TypeSettings(
      parallelism = 1,
      timeout = 30.seconds,
      pollInterval = 5.seconds
    )
  )

  def apply(config: Configuration): BackgroundJobsExecutorConfig = {
    val interval = config.get[FiniteDuration]("interval")
    BackgroundJobsExecutorConfig(interval, defaultPerType)
  }
}
