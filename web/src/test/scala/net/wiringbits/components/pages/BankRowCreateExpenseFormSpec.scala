package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.{AiSuggestions, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankRowCreateExpenseFormSpec extends AnyWordSpec {

  private val row = GetBankStatement.StatementRowData(
    id = UUID.randomUUID(),
    index = 0,
    date = LocalDate.parse("2026-01-15"),
    amount = BigDecimal("-12.50"),
    description = "OXXO STORE 123",
    manuallyEdited = false,
    confirmedAt = None,
    expenseId = None,
    refundId = None,
    ignoredAt = None,
    txnType = "debit",
    expenseMerchantId = None,
    expenseCategoryId = None
  )

  private val groceries = GetCategories.Response.Category(UUID.randomUUID(), "Groceries", Instant.now())
  private val dining = GetCategories.Response.Category(UUID.randomUUID(), "Dining", Instant.now())
  private val categories = List(groceries, dining)

  private def merchant(name: String, categoryId: Option[UUID]): GetMerchants.Response.Merchant =
    GetMerchants.Response.Merchant(
      id = UUID.randomUUID(),
      name = name,
      notes = None,
      categoryId = categoryId,
      reviewed = true,
      createdAt = Instant.now()
    )

  private val oxxo = merchant("Oxxo", Some(groceries.id))
  private val cafe = merchant("Cafe", Some(groceries.id))
  private val merchants = List(oxxo, cafe)

  private val oxxoRule =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant, "OXXO", Some(oxxo.id))

  // A description that matches no merchant name, so only the AI suggestion can prefill the form.
  private val unmatchedRow = row.copy(description = "POS 4411 UNKNOWN")

  private def suggestion(merchantId: Option[UUID], categoryId: Option[UUID]) =
    AiSuggestions.RowSuggestion(rowId = row.id, categoryId = categoryId, merchantId = merchantId)

  "initialSelection" should {
    "prefer a SetMerchant rule match over the AI suggestion" in {
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, List(oxxoRule), merchants, categories, Some(ai)) mustBe
        (Some(oxxo), Some(groceries.id))
    }

    "prefer a merchant-name match without an explicit rule over the AI suggestion" in {
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, Nil, merchants, categories, Some(ai)) mustBe
        (Some(oxxo), Some(groceries.id))
    }

    "fall through to the AI suggestion when the winning rule is an Ignore rule" in {
      val ignoreRule = ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, "OXXO", None)
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, List(ignoreRule), merchants, categories, Some(ai)) mustBe
        (Some(cafe), Some(dining.id))
    }

    "fall through to the AI suggestion when the rule's merchant is unknown" in {
      val staleRule =
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant, "OXXO", Some(UUID.randomUUID()))
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, List(staleRule), merchants, categories, Some(ai)) mustBe
        (Some(cafe), Some(dining.id))
    }

    "use a SetCategory rule's category with no merchant, ignoring the AI suggestion" in {
      val setCategory =
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory, "POS 4411", None, Some(groceries.id))
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(unmatchedRow, List(setCategory), merchants, categories, Some(ai)) mustBe
        (None, Some(groceries.id))
    }

    "drop a SetCategory rule's unknown category without falling back to the AI suggestion" in {
      val staleRule =
        ImportRuleMatcher.Rule(
          UUID.randomUUID(),
          ImportRuleAction.SetCategory,
          "POS 4411",
          None,
          Some(UUID.randomUUID())
        )
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(unmatchedRow, List(staleRule), merchants, categories, Some(ai)) mustBe
        (None, None)
    }

    "prefer a merchant-name match over a SetCategory rule" in {
      val setCategory =
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory, "OXXO STORE", None, Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, List(setCategory), merchants, categories, None) mustBe
        (Some(oxxo), Some(groceries.id))
    }

    "use the suggested merchant and category when no rule matches" in {
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(unmatchedRow, Nil, merchants, categories, Some(ai)) mustBe
        (Some(cafe), Some(dining.id))
    }

    "use a category-only suggestion" in {
      val ai = suggestion(None, Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(unmatchedRow, Nil, merchants, categories, Some(ai)) mustBe
        (None, Some(dining.id))
    }

    "drop a suggested merchant id that is not a known merchant" in {
      val ai = suggestion(Some(UUID.randomUUID()), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(unmatchedRow, Nil, merchants, categories, Some(ai)) mustBe
        (None, Some(dining.id))
    }

    "drop a suggested category id that is not a known category" in {
      val ai = suggestion(Some(cafe.id), Some(UUID.randomUUID()))
      BankRowCreateExpenseForm.initialSelection(unmatchedRow, Nil, merchants, categories, Some(ai)) mustBe
        (Some(cafe), None)
    }

    "return nothing when there is no rule match and no suggestion" in {
      BankRowCreateExpenseForm.initialSelection(unmatchedRow, Nil, merchants, categories, None) mustBe (None, None)
    }
  }

  "initialMerchantInput" should {
    "start blank for a row a SetCategory rule handles" in {
      val setCategory =
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory, "POS 4411", None, Some(groceries.id))
      BankRowCreateExpenseForm.initialMerchantInput(
        unmatchedRow,
        BankRowCreateExpenseForm.rowVerdict(unmatchedRow, List(setCategory), merchants)
      ) mustBe ""
    }

    "start with the row description otherwise" in {
      BankRowCreateExpenseForm.initialMerchantInput(
        unmatchedRow,
        BankRowCreateExpenseForm.rowVerdict(unmatchedRow, Nil, merchants)
      ) mustBe unmatchedRow.description
    }
  }

  "isPristine" should {
    "be true for the untouched blank form" in {
      BankRowCreateExpenseForm.isPristine(row, None, row.description, None, "") mustBe true
    }

    "be false once a merchant is selected" in {
      BankRowCreateExpenseForm.isPristine(row, Some(oxxo.id), row.description, None, "") mustBe false
    }

    "be false once the merchant text is edited" in {
      BankRowCreateExpenseForm.isPristine(row, None, "OXXO", None, "") mustBe false
    }

    "be false once a category is selected" in {
      BankRowCreateExpenseForm.isPristine(row, None, row.description, Some(groceries.id), "") mustBe false
    }

    "be false once the category text is edited" in {
      BankRowCreateExpenseForm.isPristine(row, None, row.description, None, "Gro") mustBe false
    }
  }

  "ruleHint" should {
    "return CoveredByRule when an explicit rule matches, even if the name also matches" in {
      val aliasRule =
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant, "OXXO STORE", Some(oxxo.id))
      BankRowCreateExpenseForm.ruleHint(row, List(aliasRule), "Oxxo") mustBe
        BankRowCreateExpenseForm.RuleHint.CoveredByRule("OXXO STORE")
    }

    "return CoveredByRule when an explicit Ignore rule matches" in {
      val ignoreRule = ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, "OXXO STORE", None)
      BankRowCreateExpenseForm.ruleHint(row, List(ignoreRule), "Oxxo") mustBe
        BankRowCreateExpenseForm.RuleHint.CoveredByRule("OXXO STORE")
    }

    "return MatchesByName when only the merchant name matches" in {
      BankRowCreateExpenseForm.ruleHint(row, Nil, "Oxxo") mustBe BankRowCreateExpenseForm.RuleHint.MatchesByName
    }

    "return NeedsAlias when the name does not occur in the description" in {
      BankRowCreateExpenseForm.ruleHint(row, Nil, "Seven Eleven") mustBe
        BankRowCreateExpenseForm.RuleHint.NeedsAlias
    }

    "return NeedsAlias when the name normalizes to under 3 characters" in {
      BankRowCreateExpenseForm.ruleHint(row, Nil, "O.X") mustBe BankRowCreateExpenseForm.RuleHint.NeedsAlias
    }
  }

  "aliasToCreate" should {
    "return None for a whitespace-only pattern" in {
      BankRowCreateExpenseForm.aliasToCreate("   ", "Oxxo") mustBe None
    }

    "return None when the pattern normalizes to the merchant name" in {
      BankRowCreateExpenseForm.aliasToCreate("O.X.X.O", "Oxxo") mustBe None
    }

    "return the trimmed pattern otherwise" in {
      BankRowCreateExpenseForm.aliasToCreate("  OXXO STORE  ", "Oxxo") mustBe Some("OXXO STORE")
    }
  }

  "categoryRuleToCreate" should {
    val needsAlias = BankRowCreateExpenseForm.RuleHint.NeedsAlias

    "return None when the user did not opt in" in {
      BankRowCreateExpenseForm.categoryRuleToCreate(needsAlias, "", Some(groceries.id), false, "OXXO STORE") mustBe None
    }

    "return None when a merchant is set" in {
      BankRowCreateExpenseForm.categoryRuleToCreate(needsAlias, "Oxxo", Some(groceries.id), true, "OXXO STORE") mustBe
        None
    }

    "return None when no category is set" in {
      BankRowCreateExpenseForm.categoryRuleToCreate(needsAlias, "", None, true, "OXXO STORE") mustBe None
    }

    "return None when an explicit rule already covers the row" in {
      BankRowCreateExpenseForm.categoryRuleToCreate(
        BankRowCreateExpenseForm.RuleHint.CoveredByRule("OXXO"),
        "",
        Some(groceries.id),
        true,
        "OXXO STORE"
      ) mustBe None
    }

    "return None for a pattern too short to match" in {
      BankRowCreateExpenseForm.categoryRuleToCreate(needsAlias, "", Some(groceries.id), true, " O.X ") mustBe None
    }

    "return the trimmed pattern otherwise" in {
      BankRowCreateExpenseForm.categoryRuleToCreate(needsAlias, "", Some(groceries.id), true, "  OXXO STORE  ") mustBe
        Some("OXXO STORE")
    }
  }
}
