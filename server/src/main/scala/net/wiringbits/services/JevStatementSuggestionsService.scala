package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.apis.{JevChoice, JevClient}
import net.wiringbits.repositories.models.{BankStatementRow, Category, Merchant}
import org.slf4j.LoggerFactory

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Suggests a category and a merchant for each debit row of a statement that no import rule already handles, using JEV
  * choice questions keyed by id. A row whose normalized description the user already filed under a category gets that
  * category without a JEV call. The other rows are grouped by normalized description and each distinct description is
  * resolved once, one after another: JEV picks a category, then, when that category has merchants, one of them (or
  * "none"). No DB access — the caller loads the inputs and persists the result.
  */
class JevStatementSuggestionsService @Inject() (jevClient: JevClient)(implicit ec: ExecutionContext) {
  import JevStatementSuggestionsService.*
  import JevClient.NoMerchant
  import StatementSuggestionsService.{finalSuggestion, historyCategories, oneLine, selectRowsToResolve}

  private val logger = LoggerFactory.getLogger(this.getClass)

  /** `examples` are (categoryId, row description) pairs of rows the user already filed under that category. `history`
    * maps a description normalized with `ImportRuleMatcher.normalize` to the category the user files it under.
    */
  def resolve(
      userId: UUID,
      statementId: UUID,
      modelId: String,
      rows: List[BankStatementRow],
      rules: List[ImportRuleMatcher.Rule],
      merchants: List[Merchant],
      categories: List[Category],
      examples: List[(UUID, String)],
      history: Map[String, UUID]
  ): Future[AiSuggestions] = {
    logger.info(s"Resolving AI suggestions for userId=$userId, statement $statementId with model $modelId")
    val selection = selectRowsToResolve(rows, rules)
    logger.info(
      s"userId=$userId, statement $statementId: ${rows.size} rows, ${selection.creditsSkipped} credits skipped, " +
        s"${selection.ruleSkipped} skipped by rule, ${selection.toResolve.size} to resolve"
    )
    val toResolve = selection.toResolve

    if (toResolve.isEmpty)
      Future.successful(AiSuggestions(status = AiSuggestionsStatus.Done, model = modelId, rows = Nil))
    else if (categories.isEmpty) {
      logger.error(s"User $userId has no categories, cannot resolve AI suggestions for statement $statementId")
      Future.failed(
        new RuntimeException(s"User $userId has no categories to suggest for statement $statementId")
      )
    } else {
      val historyHits = historyCategories(toResolve, history, categories)
      val pending = toResolve.filterNot(row => historyHits.contains(row.id))
      // Each distinct description's first row, with all the rows sharing it.
      val byKey = pending.groupBy(dedupKey)
      val groups = pending.distinctBy(dedupKey).map(first => first -> byKey(dedupKey(first)))
      logger.info(
        s"userId=$userId, statement $statementId: ${historyHits.size} rows resolved by history, " +
          s"${pending.size} rows left as ${groups.size} distinct descriptions sent to JEV"
      )
      // One description after another (keeps the JEV load predictable).
      groups
        .foldLeft(Future.successful(Map.empty[UUID, AiSuggestions.RowSuggestion])) { case (accF, (first, group)) =>
          for {
            acc <- accF
            suggestions <- resolveGroup(userId, statementId, first, group, merchants, categories, examples)
          } yield acc ++ suggestions.map(s => s.rowId -> s)
        }
        .map { resolved =>
          val suggestions = toResolve.flatMap { row =>
            historyHits
              .get(row.id)
              .map(categoryId => AiSuggestions.RowSuggestion(row.id, Some(categoryId), None))
              .orElse(resolved.get(row.id))
          }
          AiSuggestions(status = AiSuggestionsStatus.Done, model = modelId, rows = suggestions)
        }
    }
  }

  /** Resolves the rows sharing `first`'s description with a single category call and at most one merchant call. */
  private def resolveGroup(
      userId: UUID,
      statementId: UUID,
      first: BankStatementRow,
      group: List[BankStatementRow],
      merchants: List[Merchant],
      categories: List[Category],
      examples: List[(UUID, String)]
  ): Future[List[AiSuggestions.RowSuggestion]] = {
    // No amount in the state, so every row sharing the description gets the same answer.
    val state = oneLine(first.description)
    for {
      (category, categoryChoice) <- predictCategory(userId, state, categories, examples)
      candidates = merchants.filter(_.categoryId.contains(category.id))
      merchantPick <-
        if (candidates.isEmpty) Future.successful(None)
        else pickMerchant(userId, state, category, candidates).map(Some(_))
    } yield {
      val merchant = merchantPick.flatMap(_._1)
      val merchantLog = merchantPick.fold("none (no candidates)") { case (m, choice) =>
        s"${m.fold("none")(_.name)} (confidence ${choice.confidence})"
      }
      logger.info(
        s"userId=$userId, statement $statementId description '$state' (${group.size} rows): " +
          s"category ${category.name} (confidence ${categoryChoice.confidence}), merchant $merchantLog"
      )
      group.map(row => finalSuggestion(row.id, Some(category.id), merchant.map(_.id), merchants))
    }
  }

  private def predictCategory(
      userId: UUID,
      state: String,
      categories: List[Category],
      examples: List[(UUID, String)]
  ): Future[(Category, JevChoice)] =
    if (categories.size > MaxChoices)
      Future.failed(
        new RuntimeException(
          s"User $userId has ${categories.size} categories, more than the $MaxChoices JEV can choose from"
        )
      )
    else {
      val criteria = categories.map { c =>
        val categoryExamples = examples.collect {
          case (categoryId, description) if categoryId == c.id =>
            oneLine(description)
        }
        val description =
          if (categoryExamples.isEmpty) oneLine(c.name)
          else s"${oneLine(c.name)}. Examples: ${categoryExamples.mkString("; ")}"
        c.id.toString -> description
      }.toMap
      jevClient.choose(state, "category", CategoryInstructions, criteria).flatMap { choice =>
        categories.find(_.id.toString == choice.choice) match {
          case Some(category) => Future.successful((category, choice))
          case None =>
            Future.failed(
              new RuntimeException(
                s"User $userId: JEV chose '${choice.choice}', which is not one of the offered categories"
              )
            )
        }
      }
    }

  /** The chosen merchant among `candidates` (None when JEV answers "none") with JEV's choice. */
  private def pickMerchant(
      userId: UUID,
      state: String,
      category: Category,
      candidates: List[Merchant]
  ): Future[(Option[Merchant], JevChoice)] =
    if (candidates.size > MaxChoices - 1)
      Future.failed(
        new RuntimeException(
          s"User $userId has ${candidates.size} merchants in category ${category.id}, more than the " +
            s"${MaxChoices - 1} JEV can choose from"
        )
      )
    else {
      val criteria = candidates.map(m => m.id.toString -> oneLine(m.name)).toMap +
        (NoMerchant -> "No listed merchant fits this transaction")
      val instructions =
        s"""Pick the merchant that charged this bank transaction, an expense in the user's category "${oneLine(
            category.name
          )}". Choose "$NoMerchant" when no listed merchant fits."""
      jevClient.choose(state, "merchant", instructions, criteria).map { choice =>
        val merchant = candidates.find(_.id.toString == choice.choice)
        if (merchant.isEmpty && choice.choice != NoMerchant)
          logger.warn(s"User $userId: JEV chose '${choice.choice}', which is not one of the offered merchants")
        (merchant, choice)
      }
    }
}

object JevStatementSuggestionsService {
  import StatementSuggestionsService.oneLine

  /** The most options JEV accepts in a single choice question. */
  private val MaxChoices = 255

  private val CategoryInstructions =
    "Pick the user's expense category this bank transaction belongs to."

  /** Rows whose descriptions differ only in case, accents, punctuation or spacing share a key. A description that
    * normalizes to nothing keeps its raw (whitespace-collapsed) text, so unrelated junk rows are not merged.
    */
  private def dedupKey(row: BankStatementRow): String = {
    val normalized = ImportRuleMatcher.normalize(row.description)
    if (normalized.isEmpty) oneLine(row.description) else normalized
  }
}
