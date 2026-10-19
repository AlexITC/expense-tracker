package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.apis.BedrockClient
import net.wiringbits.repositories.models.{BankStatementRow, Category, Merchant}
import org.slf4j.LoggerFactory
import play.api.libs.json.*

import java.text.Normalizer
import java.util.{Locale, UUID}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Suggests a category and a merchant for each debit row of a statement that no import rule already handles. A row
  * whose normalized description the user already filed under a category gets that category without a model call. Other
  * rows are resolved one after another, each in up to two Bedrock calls: the model picks a category by name, then, when
  * that category has merchants, picks one of them by name (or "none"). A name that does not match exactly one candidate
  * is dropped (no guess); unparseable output fails the Future. No DB access — the caller loads the inputs and persists
  * the result.
  */
class StatementSuggestionsService @Inject() (bedrockClient: BedrockClient)(implicit ec: ExecutionContext) {
  import StatementSuggestionsService.*

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
    logger.info(s"Resolving AI suggestions for user $userId, statement $statementId with model $modelId")
    val selection = selectRowsToResolve(rows, rules)
    logger.info(
      s"User $userId, statement $statementId: ${rows.size} rows, ${selection.creditsSkipped} credits skipped, " +
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
      logger.info(
        s"User $userId, statement $statementId: ${historyHits.size} rows resolved by history, " +
          s"${toResolve.size - historyHits.size} sent to the model"
      )
      // One row after another (keeps the Bedrock load predictable).
      toResolve
        .foldLeft(Future.successful(List.empty[AiSuggestions.RowSuggestion])) { (accF, row) =>
          for {
            acc <- accF
            suggestion <- historyHits
              .get(row.id)
              .fold(resolveRow(userId, row, merchants, categories, examples)) { categoryId =>
                Future.successful(AiSuggestions.RowSuggestion(row.id, Some(categoryId), None))
              }
          } yield suggestion :: acc
        }
        .map { reversed =>
          val suggestions = reversed.reverse
          logResolved(userId, statementId, toResolve, suggestions, historyHits.keySet, categories, merchants)
          AiSuggestions(status = AiSuggestionsStatus.Done, model = modelId, rows = suggestions)
        }
    }
  }

  private def resolveRow(
      userId: UUID,
      row: BankStatementRow,
      merchants: List[Merchant],
      categories: List[Category],
      examples: List[(UUID, String)]
  ): Future[AiSuggestions.RowSuggestion] =
    for {
      category <- predictCategory(userId, row, categories, examples)
      merchantId <- category.fold(Future.successful(Option.empty[UUID]))(pickMerchant(userId, row, _, merchants))
    } yield finalSuggestion(row.id, category.map(_.id), merchantId, merchants)

  private def predictCategory(
      userId: UUID,
      row: BankStatementRow,
      categories: List[Category],
      examples: List[(UUID, String)]
  ): Future[Option[Category]] =
    bedrockClient.suggest(categoryPrompt(categories, examples, row)).map { raw =>
      pickByName(userId, row, "category", parseAnswer(raw), categories)(_.name)
    }

  private def pickMerchant(
      userId: UUID,
      row: BankStatementRow,
      category: Category,
      merchants: List[Merchant]
  ): Future[Option[UUID]] = {
    val candidates = merchants.filter(_.categoryId.contains(category.id))
    if (candidates.isEmpty) Future.successful(None)
    else
      bedrockClient.suggest(merchantPrompt(category, candidates, row)).map { raw =>
        val answer = parseAnswer(raw)
        if (answer.trim.equalsIgnoreCase(NoMerchant)) None
        else pickByName(userId, row, "merchant", answer, candidates)(_.name).map(_.id)
      }
  }

  private def pickByName[A](userId: UUID, row: BankStatementRow, noun: String, answer: String, candidates: List[A])(
      name: A => String
  ): Option[A] = {
    val matched = matchByName(answer, candidates)(name)
    if (matched.isEmpty)
      logger.warn(
        s"User $userId row '${row.description}': $noun answer '$answer' does not match exactly one candidate, " +
          "leaving it without a suggestion"
      )
    matched
  }

  private def logResolved(
      userId: UUID,
      statementId: UUID,
      rows: List[BankStatementRow],
      suggestions: List[AiSuggestions.RowSuggestion],
      fromHistory: Set[UUID],
      categories: List[Category],
      merchants: List[Merchant]
  ): Unit =
    rows.zip(suggestions).foreach { case (row, suggestion) =>
      val category = describe(suggestion.categoryId, id => categories.find(_.id == id).map(_.name))
      val merchant = describe(suggestion.merchantId, id => merchants.find(_.id == id).map(_.name))
      val source = if (fromHistory.contains(row.id)) "history" else "model"
      logger.info(
        s"User $userId, statement $statementId row '${row.description}': category $category, merchant $merchant " +
          s"(from $source)"
      )
    }

  private def describe(id: Option[UUID], nameOf: UUID => Option[String]): String =
    id.fold("none")(i => nameOf(i).fold(i.toString)(name => s"$i ($name)"))
}

object StatementSuggestionsService {

  private val NoMerchant = "none"

  private case class RowSelection(toResolve: List[BankStatementRow], creditsSkipped: Int, ruleSkipped: Int)

  /** Debit rows that no import rule handles; credits and rule-matched rows get no suggestion. */
  private def selectRowsToResolve(rows: List[BankStatementRow], rules: List[ImportRuleMatcher.Rule]): RowSelection = {
    val debits = rows.filter(_.amount < 0)
    val toResolve = debits.filter(r => RowRuleVerdict.verdict(rules, r.description) == RowRuleVerdict.NoRule)
    RowSelection(toResolve, rows.size - debits.size, debits.size - toResolve.size)
  }

  /** Row id → category for the rows whose normalized description is in `history`. A category that is not among the
    * loaded ones (deleted since) is dropped, so that row goes to the model.
    */
  private def historyCategories(
      rows: List[BankStatementRow],
      history: Map[String, UUID],
      categories: List[Category]
  ): Map[UUID, UUID] = {
    val categoryIds = categories.map(_.id).toSet
    rows.flatMap { row =>
      history.get(ImportRuleMatcher.normalize(row.description)).filter(categoryIds.contains).map(row.id -> _)
    }.toMap
  }

  /** The chosen merchant's category is authoritative; otherwise keep the predicted one. */
  private[services] def finalSuggestion(
      rowId: UUID,
      predictedCategoryId: Option[UUID],
      chosenMerchantId: Option[UUID],
      merchants: List[Merchant]
  ): AiSuggestions.RowSuggestion = {
    val merchant = chosenMerchantId.flatMap(id => merchants.find(_.id == id))
    AiSuggestions.RowSuggestion(rowId, merchant.flatMap(_.categoryId).orElse(predictedCategoryId), merchant.map(_.id))
  }

  /** The single candidate whose name equals `answer` ignoring case, accents and spacing; None when zero or several
    * match. Accents are folded (not deleted, unlike `ImportRuleMatcher.normalize`), so "Super" and "Súper" collide.
    */
  private[services] def matchByName[A](answer: String, candidates: List[A])(name: A => String): Option[A] = {
    val key = foldName(answer)
    candidates.filter(c => foldName(name(c)) == key) match {
      case single :: Nil => Some(single)
      case _ => None
    }
  }

  private def foldName(s: String): String =
    Normalizer
      .normalize(s, Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "")
      .toLowerCase(Locale.ROOT)
      .replaceAll("\\s+", " ")
      .trim

  private def categoryPrompt(
      categories: List[Category],
      examples: List[(UUID, String)],
      row: BankStatementRow
  ): String = {
    val categoryLines = categories.map { c =>
      val exampleLines = examples.collect {
        case (categoryId, description) if categoryId == c.id =>
          s"    example: ${oneLine(description)}"
      }
      (s"- ${oneLine(c.name)}" :: exampleLines).mkString("\n")
    }
    s"""You categorize a bank statement transaction (an expense) into one of the user's categories.
       |
       |Categories, each with descriptions of transactions the user previously filed under it:
       |${categoryLines.mkString("\n")}
       |
       |Transaction: ${oneLine(row.description)}
       |
       |${answerInstructions("category")}
       |Answer with the single best-fitting category name.""".stripMargin
  }

  private def merchantPrompt(category: Category, merchants: List[Merchant], row: BankStatementRow): String = {
    val categoryName = oneLine(category.name)
    s"""You match a bank statement transaction (an expense in the category "$categoryName") to the merchant that charged it.
       |
       |Merchants:
       |${merchants.map(m => s"- ${oneLine(m.name)}").mkString("\n")}
       |
       |Transaction: ${oneLine(row.description)}
       |
       |${answerInstructions("merchant")}
       |If no merchant clearly fits the transaction, answer "$NoMerchant".""".stripMargin
  }

  private def answerInstructions(noun: String): String =
    s"""Return ONLY a JSON object, with no prose and no markdown, in this exact shape:
       |{"answer":"<$noun name>"}
       |Use a $noun name from the list above, copied exactly.""".stripMargin

  /** Parse `{"answer":"<name>"}`. Fails loud with the raw response when it is not JSON or has no string `answer`. */
  private def parseAnswer(raw: String): String =
    Try(Json.parse(extractJsonObject(raw))).toOption
      .flatMap(json => (json \ "answer").asOpt[String])
      .getOrElse(throw new RuntimeException(s"Model did not return a valid suggestion answer: $raw"))

  private def oneLine(s: String): String = s.replaceAll("\\s+", " ").trim

  // Models sometimes wrap the JSON in markdown fences or a sentence; keep the outermost object.
  private def extractJsonObject(text: String): String = {
    val t = text.trim
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start >= 0 && end > start) t.substring(start, end + 1) else t
  }
}
