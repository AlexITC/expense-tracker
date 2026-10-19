package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
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
      txnType = "purchase"
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
}
