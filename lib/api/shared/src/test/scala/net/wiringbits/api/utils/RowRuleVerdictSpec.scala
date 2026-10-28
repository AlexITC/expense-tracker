package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class RowRuleVerdictSpec extends AnyWordSpec with Matchers {

  private def setMerchantRule(pattern: String, merchantId: UUID) =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant(merchantId), pattern)
  private def ignoreRule(pattern: String) =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, pattern)
  private def setCategoryRule(pattern: String, categoryId: UUID) =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory(categoryId), pattern)

  "verdict" should {
    "be NoRule when there are no rules" in {
      RowRuleVerdict.verdict(Nil, "MERCADONA STORE") must be(RowRuleVerdict.NoRule)
    }

    "be NoRule when no rule matches" in {
      RowRuleVerdict.verdict(List(ignoreRule("bizum")), "MERCADONA STORE") must be(RowRuleVerdict.NoRule)
    }

    "be Ignore when an ignore rule matches" in {
      RowRuleVerdict.verdict(List(ignoreRule("bizum")), "BIZUM TO JOHN") must be(RowRuleVerdict.Ignore)
    }

    "be SetMerchant with the merchant id when a set_merchant rule matches" in {
      val mid = UUID.randomUUID()
      RowRuleVerdict.verdict(List(setMerchantRule("mercadona", mid)), "MERCADONA STORE") must be(
        RowRuleVerdict.SetMerchant(mid)
      )
    }

    "prefer Ignore over SetMerchant when both match" in {
      val rules = List(setMerchantRule("mercadona store", UUID.randomUUID()), ignoreRule("mercadona"))
      RowRuleVerdict.verdict(rules, "MERCADONA STORE") must be(RowRuleVerdict.Ignore)
    }

    "be SetCategory with the category id when only a set_category rule matches" in {
      val cid = UUID.randomUUID()
      RowRuleVerdict.verdict(List(setCategoryRule("cafeteria", cid)), "CAFETERIA CENTRAL") must be(
        RowRuleVerdict.SetCategory(cid)
      )
    }

    "prefer SetMerchant over a longer set_category pattern" in {
      val mid = UUID.randomUUID()
      val rules = List(setCategoryRule("CAFETERIA", UUID.randomUUID()), setMerchantRule("CAFE", mid))
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL") must be(RowRuleVerdict.SetMerchant(mid))
    }

    "use the longer pattern between two set_category rules" in {
      val cid = UUID.randomUUID()
      val rules = List(setCategoryRule("cafe", UUID.randomUUID()), setCategoryRule("cafeteria", cid))
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL") must be(RowRuleVerdict.SetCategory(cid))
    }

    "prefer Ignore over SetMerchant and SetCategory" in {
      val rules = List(
        setMerchantRule("cafeteria central", UUID.randomUUID()),
        setCategoryRule("cafeteria central", UUID.randomUUID()),
        ignoreRule("cafe")
      )
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL") must be(RowRuleVerdict.Ignore)
    }

    "skip patterns shorter than 3 normalized chars" in {
      val rules = List(ignoreRule("m-e"), setMerchantRule("..ab", UUID.randomUUID()))
      RowRuleVerdict.verdict(rules, "ME AB STORE") must be(RowRuleVerdict.NoRule)
    }
  }

  "ruleMerchantId" should {
    "return the merchant of a winning set_merchant rule" in {
      val mid = UUID.randomUUID()
      RowRuleVerdict.ruleMerchantId(List(setMerchantRule("mercadona", mid)), "MERCADONA STORE") must be(Some(mid))
    }

    "be None when an ignore rule wins" in {
      val rules = List(setMerchantRule("mercadona", UUID.randomUUID()), ignoreRule("mercadona store"))
      RowRuleVerdict.ruleMerchantId(rules, "MERCADONA STORE") must be(None)
    }

    "be None when no rule matches" in {
      RowRuleVerdict.ruleMerchantId(Nil, "MERCADONA STORE") must be(None)
    }

    "be None when a set_category rule wins" in {
      RowRuleVerdict.ruleMerchantId(List(setCategoryRule("mercadona", UUID.randomUUID())), "MERCADONA STORE") must be(
        None
      )
    }
  }

  "ruleCategoryId" should {
    "return the category of a winning set_category rule" in {
      val cid = UUID.randomUUID()
      RowRuleVerdict.ruleCategoryId(List(setCategoryRule("mercadona", cid)), "MERCADONA STORE") must be(Some(cid))
    }

    "be None when a set_merchant rule wins" in {
      val rules =
        List(setCategoryRule("mercadona store", UUID.randomUUID()), setMerchantRule("mercadona", UUID.randomUUID()))
      RowRuleVerdict.ruleCategoryId(rules, "MERCADONA STORE") must be(None)
    }

    "be None when an ignore rule wins" in {
      val rules = List(setCategoryRule("mercadona", UUID.randomUUID()), ignoreRule("mercadona"))
      RowRuleVerdict.ruleCategoryId(rules, "MERCADONA STORE") must be(None)
    }

    "be None when no rule matches" in {
      RowRuleVerdict.ruleCategoryId(Nil, "MERCADONA STORE") must be(None)
    }
  }
}
