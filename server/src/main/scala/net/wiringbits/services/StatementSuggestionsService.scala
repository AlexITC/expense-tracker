package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus}
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.apis.BedrockClient
import net.wiringbits.repositories.models.{BankStatementRow, Category, Merchant}
import org.slf4j.LoggerFactory
import play.api.libs.json.*

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Suggests a category and a merchant for each debit row of a statement that no import rule already handles. Two steps:
  * Bedrock picks a category for every row, then, per predicted category, picks a merchant among that category's
  * merchants. Each step sends at most `BatchSize` rows per call so the answer fits the model's output-token cap. Any id
  * the model returns outside the candidate set is dropped (no guess); unparseable output fails the Future. No DB access
  * — the caller loads the inputs and persists the result.
  */
class StatementSuggestionsService @Inject() (bedrockClient: BedrockClient)(implicit ec: ExecutionContext) {
  import StatementSuggestionsService.*

  private val logger = LoggerFactory.getLogger(this.getClass)

  /** `examples` are (categoryId, row description) pairs of rows the user already filed under that category. */
  def resolve(
      statementId: UUID,
      modelId: String,
      rows: List[BankStatementRow],
      rules: List[ImportRuleMatcher.Rule],
      merchants: List[Merchant],
      categories: List[Category],
      examples: List[(UUID, String)]
  ): Future[AiSuggestions] = {
    logger.info(s"Resolving AI suggestions for statement $statementId with model $modelId")
    val selection = selectRowsToResolve(rows, rules)
    logger.info(
      s"Statement $statementId: ${rows.size} rows, ${selection.creditsSkipped} credits skipped, " +
        s"${selection.ruleSkipped} skipped by rule, ${selection.toResolve.size} to resolve"
    )
    val toResolve = selection.toResolve

    val result =
      if (toResolve.isEmpty || categories.isEmpty)
        Future.successful(toResolve.map(r => AiSuggestions.RowSuggestion(r.id, None, None)))
      else
        for {
          predicted <- predictCategories(toResolve, categories, examples)
          chosenMerchants <- pickMerchants(toResolve, predicted, merchants, categories)
        } yield toResolve.map { row =>
          finalSuggestion(row.id, predicted(row.id), chosenMerchants.get(row.id).flatten, merchants)
        }

    result.map { suggestions =>
      logResolved(statementId, toResolve, suggestions, categories, merchants)
      AiSuggestions(status = AiSuggestionsStatus.Done, model = modelId, rows = suggestions)
    }
  }

  private def predictCategories(
      rows: List[BankStatementRow],
      categories: List[Category],
      examples: List[(UUID, String)]
  ): Future[Map[UUID, Option[UUID]]] =
    inBatches(rows) { batch =>
      bedrockClient.suggest(categoryPrompt(categories, examples, batch)).map { raw =>
        validateIds(parseAnswers(raw, CategoryField), batch.map(_.id), categories.map(_.id).toSet, CategoryField)
      }
    }

  // Calls run sequentially (keeps the Bedrock load predictable); the per-batch answers are merged.
  private def inBatches(rows: List[BankStatementRow])(
      call: List[BankStatementRow] => Future[Map[UUID, Option[UUID]]]
  ): Future[Map[UUID, Option[UUID]]] =
    rows.grouped(BatchSize).foldLeft(Future.successful(Map.empty[UUID, Option[UUID]])) { (accF, batch) =>
      for {
        acc <- accF
        answers <- call(batch)
      } yield acc ++ answers
    }

  private def logResolved(
      statementId: UUID,
      rows: List[BankStatementRow],
      suggestions: List[AiSuggestions.RowSuggestion],
      categories: List[Category],
      merchants: List[Merchant]
  ): Unit =
    rows.zip(suggestions).foreach { case (row, suggestion) =>
      val category = describe(suggestion.categoryId, id => categories.find(_.id == id).map(_.name))
      val merchant = describe(suggestion.merchantId, id => merchants.find(_.id == id).map(_.name))
      logger.info(s"Statement $statementId row '${row.description}': category $category, merchant $merchant")
    }

  private def describe(id: Option[UUID], nameOf: UUID => Option[String]): String =
    id.fold("none")(i => nameOf(i).fold(i.toString)(name => s"$i ($name)"))

  // One step per predicted category that has merchants, one category after another.
  private def pickMerchants(
      rows: List[BankStatementRow],
      predicted: Map[UUID, Option[UUID]],
      merchants: List[Merchant],
      categories: List[Category]
  ): Future[Map[UUID, Option[UUID]]] =
    categories.foldLeft(Future.successful(Map.empty[UUID, Option[UUID]])) { (accF, category) =>
      val group = rows.filter(r => predicted(r.id).contains(category.id))
      val candidates = merchants.filter(_.categoryId.contains(category.id))
      if (group.isEmpty || candidates.isEmpty) accF
      else
        for {
          acc <- accF
          answers <- inBatches(group) { batch =>
            bedrockClient.suggest(merchantPrompt(category, candidates, batch)).map { raw =>
              validateIds(parseAnswers(raw, MerchantField), batch.map(_.id), candidates.map(_.id).toSet, MerchantField)
            }
          }
        } yield acc ++ answers
    }
}

object StatementSuggestionsService {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private[wiringbits] val CategoryField = "categoryId"
  private[wiringbits] val MerchantField = "merchantId"

  // Each answered row costs ~50-60 output tokens (two UUIDs); 40 rows stay well under RealBedrockClient's 4096 cap.
  private[services] val BatchSize = 40

  private case class RowSelection(toResolve: List[BankStatementRow], creditsSkipped: Int, ruleSkipped: Int)

  /** Debit rows that no import rule handles; credits and rule-matched rows get no suggestion. */
  private def selectRowsToResolve(rows: List[BankStatementRow], rules: List[ImportRuleMatcher.Rule]): RowSelection = {
    val debits = rows.filter(_.amount < 0)
    val toResolve = debits.filter(r => RowRuleVerdict.verdict(rules, r.description) == RowRuleVerdict.NoRule)
    RowSelection(toResolve, rows.size - debits.size, debits.size - toResolve.size)
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

  private def categoryPrompt(
      categories: List[Category],
      examples: List[(UUID, String)],
      rows: List[BankStatementRow]
  ): String = {
    val categoryLines = categories.map { c =>
      val exampleLines = examples.collect {
        case (categoryId, description) if categoryId == c.id =>
          s"    example: ${oneLine(description)}"
      }
      (s"- ${c.id} | ${oneLine(c.name)}" :: exampleLines).mkString("\n")
    }
    s"""You categorize bank statement transactions (expenses) into the user's categories.
       |
       |Categories (id | name), with descriptions of transactions the user previously filed under each:
       |${categoryLines.mkString("\n")}
       |
       |Transactions (id | description):
       |${rowLines(rows)}
       |
       |${answerInstructions(CategoryField, "category")}""".stripMargin
  }

  private def merchantPrompt(category: Category, merchants: List[Merchant], rows: List[BankStatementRow]): String = {
    val categoryName = oneLine(category.name)
    s"""You match bank statement transactions (expenses in the category "$categoryName") to the merchant that charged them.
       |
       |Merchants (id | name):
       |${merchants.map(m => s"- ${m.id} | ${oneLine(m.name)}").mkString("\n")}
       |
       |Transactions (id | description):
       |${rowLines(rows)}
       |
       |${answerInstructions(MerchantField, "merchant")}""".stripMargin
  }

  /** Parse `{"rows":[{"rowId":...,"<field>":...|null}]}` into rowId -> raw id (None for null/absent). Fails loud with
    * the raw response when it is not JSON or has no `rows` array.
    */
  private def parseAnswers(raw: String, field: String): Map[String, Option[String]] = {
    val entries = Try(Json.parse(extractJsonObject(raw))).toOption
      .flatMap(json => (json \ "rows").asOpt[List[JsValue]])
      .getOrElse(throw new RuntimeException(s"Model did not return a valid suggestions answer: $raw"))
    entries.flatMap { e =>
      (e \ "rowId").asOpt[String].map(rowId => rowId -> (e \ field).asOpt[String])
    }.toMap
  }

  /** Keep only answers whose id is one of `allowed`; a hallucinated id or a row missing from the answer becomes None.
    */
  private def validateIds(
      answers: Map[String, Option[String]],
      rowIds: List[UUID],
      allowed: Set[UUID],
      field: String
  ): Map[UUID, Option[UUID]] =
    rowIds.map { rowId =>
      val validated = answers.get(rowId.toString) match {
        case None =>
          logger.warn(s"Model answer is missing row $rowId ($field), leaving it without a suggestion")
          None
        case Some(None) => None
        case Some(Some(value)) =>
          val id = Try(UUID.fromString(value)).toOption.filter(allowed.contains)
          if (id.isEmpty) logger.warn(s"Model returned unknown $field '$value' for row $rowId, dropping it")
          id
      }
      rowId -> validated
    }.toMap

  private def rowLines(rows: List[BankStatementRow]): String =
    rows.map(r => s"- ${r.id} | ${oneLine(r.description)}").mkString("\n")

  private def answerInstructions(field: String, noun: String): String =
    s"""Return ONLY a JSON object, with no prose and no markdown, in this exact shape:
       |{"rows":[{"rowId":"<transaction id>","$field":"<$noun id>"}]}
       |Include every transaction exactly once. Use only $noun ids from the list above, copied exactly. If no $noun
       |clearly fits a transaction, use null for its $field.""".stripMargin

  private def oneLine(s: String): String = s.replaceAll("\\s+", " ").trim

  // Models sometimes wrap the JSON in markdown fences or a sentence; keep the outermost object.
  private def extractJsonObject(text: String): String = {
    val t = text.trim
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start >= 0 && end > start) t.substring(start, end + 1) else t
  }
}
