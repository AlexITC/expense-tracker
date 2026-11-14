package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class RowRuleVerdictSpec extends AnyWordSpec with Matchers {

  private def setMerchantRule(pattern: String, merchantId: UUID, amount: Option[BigDecimal] = None) =
    ImportRuleMatcher.Rule(
      UUID.randomUUID(),
      ImportRuleAction.SetMerchant(merchantId, categoryId = None, description = None),
      pattern,
      amount
    )
  private def ignoreRule(pattern: String, amount: Option[BigDecimal] = None) =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, pattern, amount)
  private def setCategoryRule(pattern: String, categoryId: UUID, amount: Option[BigDecimal] = None) =
    ImportRuleMatcher.Rule(
      UUID.randomUUID(),
      ImportRuleAction.SetCategory(categoryId, description = None),
      pattern,
      amount
    )
  // Rules without an amount ignore the row amount; this is any row amount for those tests.
  private val anyAmount = BigDecimal("-10.00")

  "verdict" should {
    "be NoRule when there are no rules" in {
      RowRuleVerdict.verdict(Nil, "MERCADONA STORE", anyAmount) must be(RowRuleVerdict.NoRule)
    }

    "be NoRule when no rule matches" in {
      RowRuleVerdict.verdict(List(ignoreRule("bizum")), "MERCADONA STORE", anyAmount) must be(RowRuleVerdict.NoRule)
    }

    "be Ignore when an ignore rule matches" in {
      RowRuleVerdict.verdict(List(ignoreRule("bizum")), "BIZUM TO JOHN", anyAmount) must be(RowRuleVerdict.Ignore)
    }

    "be SetMerchant with the merchant id when a set_merchant rule matches" in {
      val mid = UUID.randomUUID()
      RowRuleVerdict.verdict(List(setMerchantRule("mercadona", mid)), "MERCADONA STORE", anyAmount) must be(
        RowRuleVerdict.SetMerchant(mid, categoryId = None, description = None)
      )
    }

    "prefer Ignore over SetMerchant when both match" in {
      val rules = List(setMerchantRule("mercadona store", UUID.randomUUID()), ignoreRule("mercadona"))
      RowRuleVerdict.verdict(rules, "MERCADONA STORE", anyAmount) must be(RowRuleVerdict.Ignore)
    }

    "be SetCategory with the category id when only a set_category rule matches" in {
      val cid = UUID.randomUUID()
      RowRuleVerdict.verdict(List(setCategoryRule("cafeteria", cid)), "CAFETERIA CENTRAL", anyAmount) must be(
        RowRuleVerdict.SetCategory(cid, description = None)
      )
    }

    "carry the set_merchant rule's override category and description" in {
      val mid = UUID.randomUUID()
      val cid = UUID.randomUUID()
      val rule = ImportRuleMatcher.Rule(
        UUID.randomUUID(),
        ImportRuleAction.SetMerchant(mid, categoryId = Some(cid), description = Some("Groceries")),
        "mercadona",
        amount = None
      )
      RowRuleVerdict.verdict(List(rule), "MERCADONA STORE", anyAmount) must be(
        RowRuleVerdict.SetMerchant(mid, categoryId = Some(cid), description = Some("Groceries"))
      )
    }

    "carry the set_category rule's description" in {
      val cid = UUID.randomUUID()
      val rule = ImportRuleMatcher.Rule(
        UUID.randomUUID(),
        ImportRuleAction.SetCategory(cid, description = Some("Lunch")),
        "cafeteria",
        amount = None
      )
      RowRuleVerdict.verdict(List(rule), "CAFETERIA CENTRAL", anyAmount) must be(
        RowRuleVerdict.SetCategory(cid, description = Some("Lunch"))
      )
    }

    "prefer SetMerchant over a longer set_category pattern" in {
      val mid = UUID.randomUUID()
      val rules = List(setCategoryRule("CAFETERIA", UUID.randomUUID()), setMerchantRule("CAFE", mid))
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL", anyAmount) must be(
        RowRuleVerdict.SetMerchant(mid, categoryId = None, description = None)
      )
    }

    "use the longer pattern between two set_category rules" in {
      val cid = UUID.randomUUID()
      val rules = List(setCategoryRule("cafe", UUID.randomUUID()), setCategoryRule("cafeteria", cid))
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL", anyAmount) must be(
        RowRuleVerdict.SetCategory(cid, description = None)
      )
    }

    "prefer Ignore over SetMerchant and SetCategory" in {
      val rules = List(
        setMerchantRule("cafeteria central", UUID.randomUUID()),
        setCategoryRule("cafeteria central", UUID.randomUUID()),
        ignoreRule("cafe")
      )
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL", anyAmount) must be(RowRuleVerdict.Ignore)
    }

    "apply a rule with an amount only to rows with that exact signed amount" in {
      val cid = UUID.randomUUID()
      val rules = List(setCategoryRule("cafeteria", cid, amount = Some(BigDecimal("-3.20"))))
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL", BigDecimal("-3.2")) must be(
        RowRuleVerdict.SetCategory(cid, description = None)
      )
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL", BigDecimal("-4.00")) must be(RowRuleVerdict.NoRule)
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL", BigDecimal("3.20")) must be(RowRuleVerdict.NoRule)
    }

    "prefer a set_category rule with an amount over a longer pattern-only set_category rule" in {
      val cid = UUID.randomUUID()
      val rules = List(
        setCategoryRule("cafeteria central", UUID.randomUUID()),
        setCategoryRule("cafe", cid, amount = Some(BigDecimal("-3.20")))
      )
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL", BigDecimal("-3.20")) must be(
        RowRuleVerdict.SetCategory(cid, description = None)
      )
    }

    "skip patterns shorter than 3 normalized chars" in {
      val rules = List(ignoreRule("m-e"), setMerchantRule("..ab", UUID.randomUUID()))
      RowRuleVerdict.verdict(rules, "ME AB STORE", anyAmount) must be(RowRuleVerdict.NoRule)
    }
  }

  "ruleMerchantId" should {
    "return the merchant of a winning set_merchant rule" in {
      val mid = UUID.randomUUID()
      RowRuleVerdict.ruleMerchantId(List(setMerchantRule("mercadona", mid)), "MERCADONA STORE", anyAmount) must be(
        Some(mid)
      )
    }

    "be None when an ignore rule wins" in {
      val rules = List(setMerchantRule("mercadona", UUID.randomUUID()), ignoreRule("mercadona store"))
      RowRuleVerdict.ruleMerchantId(rules, "MERCADONA STORE", anyAmount) must be(None)
    }

    "be None when no rule matches" in {
      RowRuleVerdict.ruleMerchantId(Nil, "MERCADONA STORE", anyAmount) must be(None)
    }

    "be None when a set_category rule wins" in {
      RowRuleVerdict.ruleMerchantId(
        List(setCategoryRule("mercadona", UUID.randomUUID())),
        "MERCADONA STORE",
        anyAmount
      ) must be(
        None
      )
    }
  }

  "ruleCategoryId" should {
    "return the category of a winning set_category rule" in {
      val cid = UUID.randomUUID()
      RowRuleVerdict.ruleCategoryId(List(setCategoryRule("mercadona", cid)), "MERCADONA STORE", anyAmount) must be(
        Some(cid)
      )
    }

    "be None when a set_merchant rule wins" in {
      val rules =
        List(setCategoryRule("mercadona store", UUID.randomUUID()), setMerchantRule("mercadona", UUID.randomUUID()))
      RowRuleVerdict.ruleCategoryId(rules, "MERCADONA STORE", anyAmount) must be(None)
    }

    "be None when an ignore rule wins" in {
      val rules = List(setCategoryRule("mercadona", UUID.randomUUID()), ignoreRule("mercadona"))
      RowRuleVerdict.ruleCategoryId(rules, "MERCADONA STORE", anyAmount) must be(None)
    }

    "be None when no rule matches" in {
      RowRuleVerdict.ruleCategoryId(Nil, "MERCADONA STORE", anyAmount) must be(None)
    }
  }
}
