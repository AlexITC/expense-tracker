package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankRowCreateRefundFormSpec extends AnyWordSpec {

  private val row = GetBankStatement.StatementRowData(
    id = UUID.randomUUID(),
    index = 0,
    date = LocalDate.parse("2026-01-15"),
    amount = BigDecimal("12.50"),
    description = "OXXO STORE 123",
    manuallyEdited = false,
    confirmedAt = None,
    expenseId = None,
    refundId = None,
    ignoredAt = None,
    txnType = "credit",
    expenseMerchantId = None,
    expenseCategoryId = None
  )

  private val groceries = GetCategories.Response.Category(UUID.randomUUID(), "Groceries", Instant.now())
  private val categories = List(groceries)

  private def merchant(name: String, categoryId: Option[UUID]): GetMerchants.Response.Merchant =
    GetMerchants.Response.Merchant(UUID.randomUUID(), name, None, categoryId, None, reviewed = true, Instant.EPOCH)

  private def rule(action: ImportRuleAction): ImportRuleMatcher.Rule =
    ImportRuleMatcher.Rule(UUID.randomUUID(), action, "OXXO", amount = None)

  "initialSelection" should {
    "prefill a SetMerchant rule's merchant and its default category" in {
      val oxxo = merchant("Oxxo", Some(groceries.id))
      BankRowCreateRefundForm.initialSelection(
        row,
        List(rule(ImportRuleAction.SetMerchant(oxxo.id, categoryId = None, description = None))),
        List(oxxo),
        categories
      ) mustBe (Some(oxxo), Some(groceries.id))
    }

    "prefill a SetMerchant rule's merchant without a category when it has no default" in {
      val oxxo = merchant("Oxxo", None)
      BankRowCreateRefundForm.initialSelection(
        row,
        List(rule(ImportRuleAction.SetMerchant(oxxo.id, categoryId = None, description = None))),
        List(oxxo),
        categories
      ) mustBe (Some(oxxo), None)
    }

    "prefill a SetCategory rule's category with no merchant" in {
      BankRowCreateRefundForm.initialSelection(
        row,
        List(rule(ImportRuleAction.SetCategory(groceries.id, description = None))),
        Nil,
        categories
      ) mustBe (None, Some(groceries.id))
    }

    "prefill the merchant whose name matches the description without an explicit rule" in {
      val oxxo = merchant("Oxxo", Some(groceries.id))
      BankRowCreateRefundForm.initialSelection(row, Nil, List(oxxo), categories) mustBe
        (Some(oxxo), Some(groceries.id))
    }

    "prefill nothing when the winning rule is an Ignore rule" in {
      BankRowCreateRefundForm.initialSelection(
        row,
        List(rule(ImportRuleAction.Ignore)),
        Nil,
        categories
      ) mustBe (None, None)
    }

    "prefill nothing when no rule matches" in {
      BankRowCreateRefundForm.initialSelection(row, Nil, Nil, categories) mustBe (None, None)
    }

    "prefill nothing when a SetMerchant rule's merchant is not among the user's merchants" in {
      BankRowCreateRefundForm.initialSelection(
        row,
        List(rule(ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None))),
        Nil,
        categories
      ) mustBe (None, None)
    }

    "drop a SetCategory rule's category that is not among the user's categories" in {
      BankRowCreateRefundForm.initialSelection(
        row,
        List(rule(ImportRuleAction.SetCategory(UUID.randomUUID(), description = None))),
        Nil,
        categories
      ) mustBe (None, None)
    }

    "use a SetMerchant rule's override category over the merchant's default category" in {
      val other = GetCategories.Response.Category(UUID.randomUUID(), "Other", Instant.now())
      val oxxo = merchant("Oxxo", Some(groceries.id))
      BankRowCreateRefundForm.initialSelection(
        row,
        List(rule(ImportRuleAction.SetMerchant(oxxo.id, categoryId = Some(other.id), description = None))),
        List(oxxo),
        other :: categories
      ) mustBe (Some(oxxo), Some(other.id))
    }
  }
}
