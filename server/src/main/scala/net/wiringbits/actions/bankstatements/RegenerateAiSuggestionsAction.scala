package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, RegenerateAiSuggestions}
import net.wiringbits.config.BedrockConfig
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.BankStatementRepository
import net.wiringbits.repositories.models.BackgroundJobData
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Reset a statement's AI suggestions to Pending and enqueue the job that recomputes them. The repository fails with
  * "Bank statement not found" when the statement does not belong to the user, enqueuing nothing.
  */
class RegenerateAiSuggestionsAction @Inject() (
    bankStatementRepository: BankStatementRepository,
    bedrockConfig: BedrockConfig
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(bankStatementId: UUID, userId: UUID): Future[RegenerateAiSuggestions.Response] = {
    val now = clock.instant()
    val pending = AiSuggestions(AiSuggestionsStatus.Pending, bedrockConfig.suggestionsModelId, Nil)
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ResolveStatementSuggestions,
      payload = BackgroundJobPayload.ResolveStatementSuggestions(bankStatementId),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
    for {
      _ <- bankStatementRepository.requestAiSuggestions(bankStatementId, userId, pending, job, now)
    } yield {
      logger.info(s"Requested AI suggestions for statement $bankStatementId, enqueued suggestions job ${job.id}")
      RegenerateAiSuggestions.Response(pending)
    }
  }
}
