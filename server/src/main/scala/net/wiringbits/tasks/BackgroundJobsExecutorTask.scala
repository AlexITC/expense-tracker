package net.wiringbits.tasks

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import com.google.inject.Inject
import net.wiringbits.actions.documents.{ParseReceiptAction, PollReceiptAction, ProcessDocumentAction}
import net.wiringbits.actions.bankstatements.{ExtractBankStatementDraftAction, ResolveStatementSuggestionsAction}
import net.wiringbits.apis.EmailApi
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.config.BackgroundJobsExecutorConfig
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobType}
import net.wiringbits.repositories.models.BackgroundJobData
import net.wiringbits.util.EmailMessage
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class BackgroundJobsExecutorTask @Inject() (
    config: BackgroundJobsExecutorConfig,
    runner: BackgroundJobTypeRunner,
    emailApi: EmailApi,
    processDocumentAction: ProcessDocumentAction,
    pollReceiptAction: PollReceiptAction,
    parseReceiptAction: ParseReceiptAction,
    extractBankStatementDraftAction: ExtractBankStatementDraftAction,
    resolveStatementSuggestionsAction: ResolveStatementSuggestionsAction
)(implicit
    ec: ExecutionContext,
    actorSystem: ActorSystem
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  logger.info("Starting the background jobs executor task")
  // resolved eagerly so a missing type fails at startup
  private val settingsPerType = BackgroundJobType.values.map(t => t -> config.settingsFor(t))
  settingsPerType.foreach { case (jobType, settings) =>
    actorSystem.scheduler.scheduleOnce(config.interval) {
      run(jobType, settings)
    }
  }

  private def dispatch(job: BackgroundJobData): Future[Unit] = {
    job.`type` match {
      case BackgroundJobType.SendEmail =>
        job.payload.asOpt[BackgroundJobPayload.SendEmail] match {
          case Some(typedPayload) => sendEmail(typedPayload)
          case None =>
            Future.failed(
              new RuntimeException(
                s"The given payload is not supported by the SendEmail task, please double check, job id = ${job.id}"
              )
            )
        }

      case BackgroundJobType.ProcessDocument =>
        job.payload.asOpt[BackgroundJobPayload.ProcessDocument] match {
          case Some(typedPayload) =>
            logger.info(s"Dispatching ProcessDocument job ${job.id}, documentId=${typedPayload.documentId}")
            processDocumentAction(typedPayload.documentId)
          case None =>
            Future.failed(
              new RuntimeException(
                s"The given payload is not supported by the ProcessDocument task, please double check, job id = ${job.id}"
              )
            )
        }

      // Legacy Textract statement jobs: the flow was retired (statements import via the AI draft flow). Kept as a
      // no-op so any leftover jobs in the queue complete instead of breaking the executor.
      case BackgroundJobType.PollBankStatement | BackgroundJobType.ParseBankStatement =>
        logger.info(s"Skipping legacy statement job ${job.id} (${job.`type`}); Textract statement flow was retired")
        Future.unit

      case BackgroundJobType.PollReceipt =>
        job.payload.asOpt[BackgroundJobPayload.PollReceipt] match {
          case Some(typedPayload) =>
            logger.info(s"Dispatching PollReceipt job ${job.id}, documentId=${typedPayload.documentId}")
            pollReceiptAction(typedPayload.documentId)
          case None =>
            Future.failed(
              new RuntimeException(
                s"The given payload is not supported by the PollReceipt task, please double check, job id = ${job.id}"
              )
            )
        }

      case BackgroundJobType.ParseReceipt =>
        job.payload.asOpt[BackgroundJobPayload.ParseReceipt] match {
          case Some(typedPayload) =>
            logger.info(s"Dispatching ParseReceipt job ${job.id}, documentId=${typedPayload.documentId}")
            parseReceiptAction(typedPayload.documentId)
          case None =>
            Future.failed(
              new RuntimeException(
                s"The given payload is not supported by the ParseReceipt task, please double check, job id = ${job.id}"
              )
            )
        }

      case BackgroundJobType.ExtractBankStatementDraft =>
        job.payload.asOpt[BackgroundJobPayload.ExtractBankStatementDraft] match {
          case Some(typedPayload) =>
            logger.info(s"Dispatching ExtractBankStatementDraft job ${job.id}, draftId=${typedPayload.draftId}")
            extractBankStatementDraftAction(typedPayload.draftId)
          case None =>
            Future.failed(
              new RuntimeException(
                s"The given payload is not supported by the ExtractBankStatementDraft task, please double check, job id = ${job.id}"
              )
            )
        }

      case BackgroundJobType.ResolveStatementSuggestions =>
        job.payload.asOpt[BackgroundJobPayload.ResolveStatementSuggestions] match {
          case Some(typedPayload) =>
            logger.info(
              s"Dispatching ResolveStatementSuggestions job ${job.id}, bankStatementId=${typedPayload.bankStatementId}"
            )
            resolveStatementSuggestionsAction(typedPayload.bankStatementId)
          case None =>
            Future.failed(
              new RuntimeException(
                s"The given payload is not supported by the ResolveStatementSuggestions task, please double check, job id = ${job.id}"
              )
            )
        }
    }
  }

  // TODO: Move to another file
  private def sendEmail(payload: BackgroundJobPayload.SendEmail): Future[Unit] = {
    val emailRequest = EmailRequest(payload.email, EmailMessage(subject = payload.subject, body = payload.body))
    emailApi.sendEmail(emailRequest)
  }

  private def run(jobType: BackgroundJobType, settings: BackgroundJobsExecutorConfig.TypeSettings): Unit = {
    val result = runner.source(jobType, settings, dispatch).runWith(Sink.ignore)

    result.onComplete {
      case Failure(ex) =>
        logger.error(
          s"Failed to process pending $jobType background jobs, retrying after ${config.interval}: ${ex.getMessage}",
          ex
        )
        actorSystem.scheduler.scheduleOnce(config.interval) { run(jobType, settings) }

      case Success(_) => actorSystem.scheduler.scheduleOnce(config.interval) { run(jobType, settings) }
    }
  }
}
