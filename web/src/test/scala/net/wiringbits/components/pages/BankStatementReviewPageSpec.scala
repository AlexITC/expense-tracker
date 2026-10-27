package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
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

  "primaryActionFor" should {
    import BankStatementReviewPage.{PrimaryAction, primaryActionFor}
    val negative = sampleRow()
    val positive = sampleRow().copy(amount = BigDecimal("45.00"))
    val setMerchant = RowRuleVerdict.SetMerchant(Some(UUID.randomUUID()))

    "review a ready negative row with a SetMerchant verdict as an expense" in {
      primaryActionFor(negative, isReady = true, setMerchant) mustBe PrimaryAction.ReviewExpense
    }

    "review a ready positive row as a refund" in {
      primaryActionFor(positive, isReady = true, RowRuleVerdict.NoRule) mustBe PrimaryAction.ReviewRefund
    }

    "ignore a ready row with an Ignore verdict in one click" in {
      primaryActionFor(negative, isReady = true, RowRuleVerdict.Ignore) mustBe PrimaryAction.IgnoreByRule
    }

    "show Linked for a linked row even when it is ready" in {
      primaryActionFor(negative.copy(expenseId = Some(UUID.randomUUID())), isReady = true, setMerchant) mustBe
        PrimaryAction.Linked
      primaryActionFor(positive.copy(refundId = Some(UUID.randomUUID())), isReady = true, setMerchant) mustBe
        PrimaryAction.Linked
    }

    "offer Unignore for an ignored row" in {
      primaryActionFor(negative.copy(ignoredAt = Some(Instant.now())), isReady = false, RowRuleVerdict.Ignore) mustBe
        PrimaryAction.Unignore
    }

    "create an expense for a not-ready negative row" in {
      primaryActionFor(negative, isReady = false, RowRuleVerdict.NoRule) mustBe PrimaryAction.CreateExpense
    }

    "create a refund for a not-ready positive row" in {
      primaryActionFor(positive, isReady = false, RowRuleVerdict.NoRule) mustBe PrimaryAction.CreateRefund
    }
  }

  "rowInfo" should {
    import BankStatementReviewPage.rowInfo

    def merchant(id: UUID, name: String, categoryId: Option[UUID]): GetMerchants.Response.Merchant =
      GetMerchants.Response.Merchant(id, name, None, categoryId, None, reviewed = true, Instant.EPOCH)

    def rule(action: ImportRuleAction, pattern: String, merchantId: Option[UUID]): ImportRuleMatcher.Rule =
      ImportRuleMatcher.Rule(UUID.randomUUID(), action, pattern, merchantId)

    "mark an unprocessed negative row with an Ignore rule as ready, with nothing to import" in {
      val info = rowInfo(List(rule(ImportRuleAction.Ignore, "SUPERMARKET", None)), Nil, Nil, None, sampleRow())
      info.verdict mustBe RowRuleVerdict.Ignore
      info.resolved mustBe None
      info.isReady mustBe true
    }

    "mark a row with a resolved import as ready and expose the merchant and category names" in {
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      val info = rowInfo(
        List(rule(ImportRuleAction.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, "Walmart", Some(c))),
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        sampleRow()
      )
      info.verdict mustBe RowRuleVerdict.SetMerchant(Some(m))
      info.resolved mustBe Some(BankStatementReviewPage.ResolvedImport(Some(m), c, categoryFromAi = false))
      info.isReady mustBe true
      info.merchantName mustBe Some("Walmart")
      info.categoryName mustBe Some("Groceries")
    }

    "resolve a row matched only by a merchant name to that merchant and its category" in {
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      val merchants = List(merchant(m, "Supermarket", Some(c)))
      val info = rowInfo(
        ImportRuleMatcher.effectiveRules(Nil, merchants.map(mm => mm.id -> mm.name)),
        merchants,
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        sampleRow("POS SUPERMARKET 0042")
      )
      info.verdict mustBe RowRuleVerdict.SetMerchant(Some(m))
      info.resolved mustBe Some(BankStatementReviewPage.ResolvedImport(Some(m), c, categoryFromAi = false))
      info.isReady mustBe true
      info.merchantName mustBe Some("Supermarket")
      info.categoryName mustBe Some("Groceries")
    }

    "take the category name from an AI suggestion" in {
      val c = UUID.randomUUID()
      val row = sampleRow("UNKNOWN SHOP")
      val ai = Some(
        AiSuggestions(AiSuggestionsStatus.Done, "m", List(AiSuggestions.RowSuggestion(row.id, Some(c), None)))
      )
      val info = rowInfo(Nil, Nil, List(GetCategories.Response.Category(c, "Dining", Instant.EPOCH)), ai, row)
      info.resolved.map(_.categoryFromAi) mustBe Some(true)
      info.merchantName mustBe None
      info.categoryName mustBe Some("Dining")
    }

    "expose the linked expense's merchant and category names" in {
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      val row =
        sampleRow().copy(expenseId = Some(UUID.randomUUID()), expenseMerchantId = Some(m), expenseCategoryId = Some(c))
      val info = rowInfo(
        Nil,
        List(merchant(m, "Walmart", None)),
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        row
      )
      info.linkedMerchantName mustBe Some("Walmart")
      info.linkedCategory mustBe BankStatementReviewPage.LinkedCategory.Named("Groceries")
    }

    "not mark a row ready when nothing resolves" in {
      val info = rowInfo(Nil, Nil, Nil, None, sampleRow("UNKNOWN SHOP"))
      info.isReady mustBe false
      info.merchantName mustBe None
      info.categoryName mustBe None
    }

    "not mark an already linked or a positive row ready" in {
      val rules = List(rule(ImportRuleAction.Ignore, "SUPERMARKET", None))
      rowInfo(rules, Nil, Nil, None, sampleRow().copy(expenseId = Some(UUID.randomUUID()))).isReady mustBe false
      rowInfo(rules, Nil, Nil, None, sampleRow().copy(amount = BigDecimal("10.00"))).isReady mustBe false
    }
  }

  "menuItemsFor" should {
    import BankStatementReviewPage.{RowMenuItem, menuItemsFor}

    "offer only Unlink for a row linked to an expense" in {
      menuItemsFor(sampleRow().copy(expenseId = Some(UUID.randomUUID()))) mustBe List(RowMenuItem.Unlink)
    }

    "offer only UnlinkRefund for a row linked to a refund" in {
      menuItemsFor(sampleRow().copy(amount = BigDecimal("45.00"), refundId = Some(UUID.randomUUID()))) mustBe
        List(RowMenuItem.UnlinkRefund)
    }

    "offer only Unignore for an ignored row" in {
      menuItemsFor(sampleRow().copy(ignoredAt = Some(Instant.now()))) mustBe List(RowMenuItem.Unignore)
    }

    "offer link, rule and ignore for an unprocessed row" in {
      menuItemsFor(sampleRow("UNKNOWN SHOP")) mustBe
        List(RowMenuItem.LinkExpense, RowMenuItem.CreateRule, RowMenuItem.Ignore)
    }
  }

  "linkedCategoryDisplay" should {
    import BankStatementReviewPage.{LinkedCategory, linkedCategoryDisplay}
    val c = UUID.randomUUID()
    val categories = List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH))
    val linked = sampleRow().copy(expenseId = Some(UUID.randomUUID()))

    "resolve a single category to its name" in {
      linkedCategoryDisplay(linked.copy(expenseCategoryId = Some(c)), categories) mustBe
        LinkedCategory.Named("Groceries")
    }

    "show Multiple for a linked expense without a single category" in {
      linkedCategoryDisplay(linked, categories) mustBe LinkedCategory.Multiple
    }

    "be Unknown when the category is not in the list" in {
      linkedCategoryDisplay(linked.copy(expenseCategoryId = Some(UUID.randomUUID())), categories) mustBe
        LinkedCategory.Unknown
    }

    "be Unknown for a refund-only link" in {
      linkedCategoryDisplay(sampleRow().copy(refundId = Some(UUID.randomUUID())), categories) mustBe
        LinkedCategory.Unknown
    }
  }

  "linkedMerchantName" should {
    import BankStatementReviewPage.linkedMerchantName
    val m = UUID.randomUUID()
    val merchants =
      List(GetMerchants.Response.Merchant(m, "Walmart", None, None, None, reviewed = true, Instant.EPOCH))
    val linked = sampleRow().copy(expenseId = Some(UUID.randomUUID()))

    "resolve the linked expense's merchant to its name" in {
      linkedMerchantName(linked.copy(expenseMerchantId = Some(m)), merchants) mustBe Some("Walmart")
    }

    "be None when the expense has no merchant" in {
      linkedMerchantName(linked, merchants) mustBe None
    }

    "be None when the merchant is not in the list" in {
      linkedMerchantName(linked.copy(expenseMerchantId = Some(UUID.randomUUID())), merchants) mustBe None
    }
  }
}
