package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class TextractJob(
    id: UUID,
    userId: UUID,
    documentId: UUID,
    apiType: String,
    jobId: Option[String],
    status: TextractJob.Status,
    attempts: Int,
    lastPolledAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant
)

object TextractJob {
  sealed trait Status
  object Status {
    case object PendingStart extends Status
    case object InProgress extends Status
    case object Succeeded extends Status
    case object Failed extends Status

    def fromString(s: String): Status = s match {
      case "pending_start" => PendingStart
      case "in_progress" => InProgress
      case "succeeded" => Succeeded
      case "failed" => Failed
      case other => throw new NoSuchElementException(s"Unknown TextractJob.Status: $other")
    }
    def entryName(s: Status): String = s match {
      case PendingStart => "pending_start"
      case InProgress => "in_progress"
      case Succeeded => "succeeded"
      case Failed => "failed"
    }
  }

  case class Create(
      id: UUID,
      userId: UUID,
      documentId: UUID,
      apiType: String,
      jobId: String,
      status: Status,
      createdAt: Instant,
      updatedAt: Instant
  )
}
