package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementReviewPageSpec extends AnyWordSpec {

  private def sampleRow(description: String = "SUPERMARKET"): GetBankStatement.StatementRowData =
    GetBankStatement.StatementRowData(
      id = UUID.randomUUID(),
      index = 0,
      date = LocalDate.of(2024, 3, 7),
      amount = BigDecimal("-120.00"),
      description = description,
      manuallyEdited = false,
      confirmedAt = None,
      expenseId = None,
      refundId = None,
      ignoredAt = None,
      txnType = "purchase",
      expenseMerchantId = None,
      expenseCategoryId = None
    )

  "BankStatementReviewPage.resultLabel" should {
    "use row description in success label" in {
      val row = sampleRow(description = "WHOLE FOODS MARKET")
      BankStatementReviewPage.resultLabel(
        row.id,
        Map(row.id -> row.description),
        Right(())
      ) mustBe "✓ WHOLE FOODS MARKET"
    }

    "use row description in failure label" in {
      val row = sampleRow(description = "WHOLE FOODS MARKET")
      BankStatementReviewPage.resultLabel(row.id, Map(row.id -> row.description), Left("No default category")) mustBe
        "✗ WHOLE FOODS MARKET: No default category"
    }

    "fall back to UUID prefix when row not found in map" in {
      val id = UUID.fromString("abcdef12-0000-0000-0000-000000000000")
      BankStatementReviewPage.resultLabel(id, Map.empty, Right(())) mustBe "✓ abcdef12"
    }

    "fall back to UUID prefix in failure label when row not found" in {
      val id = UUID.fromString("abcdef12-0000-0000-0000-000000000000")
      BankStatementReviewPage.resultLabel(id, Map.empty, Left("timeout")) mustBe "✗ abcdef12: timeout"
    }

    "use empty string description as-is in success label" in {
      val row = sampleRow(description = "")
      BankStatementReviewPage.resultLabel(row.id, Map(row.id -> row.description), Right(())) mustBe "✓ "
    }
  }

  "suggestionsPending" should {
    def suggestions(status: AiSuggestionsStatus) = Some(AiSuggestions(status, "eu.amazon.nova-lite-v1:0", Nil))

    "be true when suggestions have not been written yet" in {
      BankStatementReviewPage.suggestionsPending(None) mustBe true
    }

    "be true when the status is Pending" in {
      BankStatementReviewPage.suggestionsPending(suggestions(AiSuggestionsStatus.Pending)) mustBe true
    }

    "be false when the status is Done" in {
      BankStatementReviewPage.suggestionsPending(suggestions(AiSuggestionsStatus.Done)) mustBe false
    }

    "be false when the status is Failed" in {
      BankStatementReviewPage.suggestionsPending(suggestions(AiSuggestionsStatus.Failed)) mustBe false
    }
  }

  "canRegenerateSuggestions" should {
    def suggestions(status: AiSuggestionsStatus) = Some(AiSuggestions(status, "eu.amazon.nova-lite-v1:0", Nil))

    "be true when suggestions have never been generated" in {
      BankStatementReviewPage.canRegenerateSuggestions(None, pollAttemptsExhausted = false) mustBe true
    }

    "be false when the status is Pending and polling is still active" in {
      BankStatementReviewPage.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Pending),
        pollAttemptsExhausted = false
      ) mustBe false
    }

    "be true when the status is Pending but the poll attempts are exhausted" in {
      BankStatementReviewPage.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Pending),
        pollAttemptsExhausted = true
      ) mustBe true
    }

    "be true when the status is Done" in {
      BankStatementReviewPage.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Done),
        pollAttemptsExhausted = false
      ) mustBe true
    }

    "be true when the status is Failed" in {
      BankStatementReviewPage.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Failed),
        pollAttemptsExhausted = false
      ) mustBe true
    }
  }

  "resolveImport" should {
    def merchant(id: UUID, categoryId: Option[UUID]): GetMerchants.Response.Merchant =
      GetMerchants.Response.Merchant(id, "Merchant", None, categoryId, None, reviewed = true, Instant.EPOCH)

    def rule(action: ImportRuleAction, pattern: String, merchantId: Option[UUID]): ImportRuleMatcher.Rule =
      ImportRuleMatcher.Rule(UUID.randomUUID(), action, pattern, merchantId)

    def done(rows: AiSuggestions.RowSuggestion*): Option[AiSuggestions] =
      Some(AiSuggestions(AiSuggestionsStatus.Done, "eu.amazon.nova-lite-v1:0", rows.toList))

    "take the category from a rule-matched merchant, not AI" in {
      val row = sampleRow("SUPERMARKET")
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      BankStatementReviewPage.resolveImport(
        List(rule(ImportRuleAction.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, Some(c))),
        aiSuggestions = None,
        row
      ) mustBe Some(BankStatementReviewPage.ResolvedImport(Some(m), c, categoryFromAi = false))
    }

    "fall back to the AI category with no merchant when no rule matches" in {
      val row = sampleRow("UNKNOWN SHOP")
      val c = UUID.randomUUID()
      BankStatementReviewPage.resolveImport(
        Nil,
        Nil,
        done(AiSuggestions.RowSuggestion(row.id, Some(c), None)),
        row
      ) mustBe Some(BankStatementReviewPage.ResolvedImport(None, c, categoryFromAi = true))
    }

    "use the AI-suggested merchant and category when no rule matches" in {
      val row = sampleRow("UNKNOWN SHOP")
      val c = UUID.randomUUID()
      val m = UUID.randomUUID()
      BankStatementReviewPage.resolveImport(
        Nil,
        Nil,
        done(AiSuggestions.RowSuggestion(row.id, Some(c), Some(m))),
        row
      ) mustBe Some(BankStatementReviewPage.ResolvedImport(Some(m), c, categoryFromAi = true))
    }

    "keep the rule merchant but take the category from AI when the merchant has no default category" in {
      val row = sampleRow("SUPERMARKET")
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      BankStatementReviewPage.resolveImport(
        List(rule(ImportRuleAction.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, None)),
        done(AiSuggestions.RowSuggestion(row.id, Some(c), None)),
        row
      ) mustBe Some(BankStatementReviewPage.ResolvedImport(Some(m), c, categoryFromAi = true))
    }

    "resolve to None for an Ignore rule even when AI has a suggestion" in {
      val row = sampleRow("SUPERMARKET")
      val c = UUID.randomUUID()
      BankStatementReviewPage.resolveImport(
        List(rule(ImportRuleAction.Ignore, "SUPERMARKET", None)),
        Nil,
        done(AiSuggestions.RowSuggestion(row.id, Some(c), None)),
        row
      ) mustBe None
    }

    "ignore AI suggestions that are still Pending" in {
      val row = sampleRow("UNKNOWN SHOP")
      val c = UUID.randomUUID()
      val pending =
        Some(AiSuggestions(AiSuggestionsStatus.Pending, "m", List(AiSuggestions.RowSuggestion(row.id, Some(c), None))))
      BankStatementReviewPage.resolveImport(Nil, Nil, pending, row) mustBe None
    }

    "resolve to None when nothing matches and there is no suggestion" in {
      BankStatementReviewPage.resolveImport(Nil, Nil, None, sampleRow("UNKNOWN SHOP")) mustBe None
    }
  }
}
