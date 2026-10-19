package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.utils.ImportRuleMatcher
import io.scalaland.chimney.dsl.*
import net.wiringbits.config.BedrockConfig
import net.wiringbits.repositories.{
  BankStatementRepository,
  CategoriesRepository,
  ImportRuleRepository,
  MerchantsRepository
}
import net.wiringbits.repositories.models.BankStatement
import net.wiringbits.services.StatementSuggestionsService
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Background job: compute the AI category/merchant suggestions for a confirmed statement and store them with a single
  * write (Done). On failure a Failed result is stored and the error is re-raised, so the executor records it and
  * retries with backoff; a later successful retry overwrites it with Done.
  */
class ResolveStatementSuggestionsAction @Inject() (
    bankStatementRepository: BankStatementRepository,
    importRuleRepository: ImportRuleRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository,
    suggestionsService: StatementSuggestionsService,
    bedrockConfig: BedrockConfig
)(implicit ec: ExecutionContext, clock: Clock) {

  import ResolveStatementSuggestionsAction.*

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(bankStatementId: UUID): Future[Unit] = {
    val modelId = bedrockConfig.suggestionsModelId
    logger.info(s"Resolving AI suggestions job for statement $bankStatementId")
    bankStatementRepository.findInternal(bankStatementId).flatMap {
      case None => Future.failed(new RuntimeException(s"Bank statement $bankStatementId not found"))
      case Some(statement) =>
        compute(statement, modelId)
          .flatMap(store(statement, _))
          .recoverWith { case NonFatal(ex) => recordFailure(statement, modelId, ex) }
    }
  }

  private def compute(statement: BankStatement, modelId: String): Future[AiSuggestions] = {
    val userId = statement.userId
    for {
      rows <- bankStatementRepository.findRows(statement.id)
      importRules <- importRuleRepository.all(userId)
      merchants <- merchantsRepository.all(userId)
      categories <- categoriesRepository.all(userId)
      examples <- bankStatementRepository.categorizedRowExamples(userId, ExamplesPerCategory)
      rules = importRules.map(_.transformInto[ImportRuleMatcher.Rule])
      suggestions <- suggestionsService.resolve(
        userId,
        statement.id,
        modelId,
        rows,
        rules,
        merchants,
        categories,
        examples
      )
    } yield suggestions
  }

  private def store(statement: BankStatement, suggestions: AiSuggestions): Future[Unit] =
    bankStatementRepository.setAiSuggestions(statement.id, statement.userId, suggestions, clock.instant()).map { _ =>
      logger.info(
        s"Stored AI suggestions for statement ${statement.id}: status ${suggestions.status.entryName}, " +
          s"${suggestions.rows.size} rows, model ${suggestions.model}"
      )
    }

  // Always re-raise the original cause so the executor records it, even if the Failed write itself fails.
  private def recordFailure(statement: BankStatement, modelId: String, ex: Throwable): Future[Nothing] = {
    logger.error(s"Resolving AI suggestions for statement ${statement.id} failed: ${ex.getMessage}", ex)
    store(statement, AiSuggestions(AiSuggestionsStatus.Failed, modelId, Nil))
      .recover { case NonFatal(writeEx) =>
        logger.error(s"Could not store Failed AI suggestions for statement ${statement.id}", writeEx)
      }
      .flatMap(_ => Future.failed(ex))
  }
}

object ResolveStatementSuggestionsAction {
  private val ExamplesPerCategory = 3
}
