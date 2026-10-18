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
    txnType = "debit"
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

  private def suggestion(merchantId: Option[UUID], categoryId: Option[UUID]) =
    AiSuggestions.RowSuggestion(rowId = row.id, categoryId = categoryId, merchantId = merchantId)

  "initialSelection" should {
    "prefer a SetMerchant rule match over the AI suggestion" in {
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, List(oxxoRule), merchants, categories, Some(ai)) mustBe
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

    "use the suggested merchant and category when no rule matches" in {
      val ai = suggestion(Some(cafe.id), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, Nil, merchants, categories, Some(ai)) mustBe
        (Some(cafe), Some(dining.id))
    }

    "use a category-only suggestion" in {
      val ai = suggestion(None, Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, Nil, merchants, categories, Some(ai)) mustBe
        (None, Some(dining.id))
    }

    "drop a suggested merchant id that is not a known merchant" in {
      val ai = suggestion(Some(UUID.randomUUID()), Some(dining.id))
      BankRowCreateExpenseForm.initialSelection(row, Nil, merchants, categories, Some(ai)) mustBe
        (None, Some(dining.id))
    }

    "drop a suggested category id that is not a known category" in {
      val ai = suggestion(Some(cafe.id), Some(UUID.randomUUID()))
      BankRowCreateExpenseForm.initialSelection(row, Nil, merchants, categories, Some(ai)) mustBe
        (Some(cafe), None)
    }

    "return nothing when there is no rule match and no suggestion" in {
      BankRowCreateExpenseForm.initialSelection(row, Nil, merchants, categories, None) mustBe (None, None)
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
}
