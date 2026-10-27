package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class RowRuleVerdictSpec extends AnyWordSpec with Matchers {

  private def setMerchantRule(pattern: String, merchantId: Option[UUID]) =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant, pattern, merchantId)
  private def ignoreRule(pattern: String) =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, pattern, None)
  private def setCategoryRule(pattern: String, categoryId: UUID) =
    ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory, pattern, None, Some(categoryId))

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
      RowRuleVerdict.verdict(List(setMerchantRule("mercadona", Some(mid))), "MERCADONA STORE") must be(
        RowRuleVerdict.SetMerchant(Some(mid))
      )
    }

    "be SetMerchant without a merchant id when the matching set_merchant rule has none" in {
      RowRuleVerdict.verdict(List(setMerchantRule("mercadona", None)), "MERCADONA STORE") must be(
        RowRuleVerdict.SetMerchant(None)
      )
    }

    "prefer Ignore over SetMerchant when both match" in {
      val rules = List(setMerchantRule("mercadona store", Some(UUID.randomUUID())), ignoreRule("mercadona"))
      RowRuleVerdict.verdict(rules, "MERCADONA STORE") must be(RowRuleVerdict.Ignore)
    }

    "be SetCategory with the category id when only a set_category rule matches" in {
      val cid = UUID.randomUUID()
      RowRuleVerdict.verdict(List(setCategoryRule("cafeteria", cid)), "CAFETERIA CENTRAL") must be(
        RowRuleVerdict.SetCategory(Some(cid))
      )
    }

    "prefer SetMerchant over a longer set_category pattern" in {
      val mid = UUID.randomUUID()
      val rules = List(setCategoryRule("CAFETERIA", UUID.randomUUID()), setMerchantRule("CAFE", Some(mid)))
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL") must be(RowRuleVerdict.SetMerchant(Some(mid)))
    }

    "use the longer pattern between two set_category rules" in {
      val cid = UUID.randomUUID()
      val rules = List(setCategoryRule("cafe", UUID.randomUUID()), setCategoryRule("cafeteria", cid))
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL") must be(RowRuleVerdict.SetCategory(Some(cid)))
    }

    "prefer Ignore over SetMerchant and SetCategory" in {
      val rules = List(
        setMerchantRule("cafeteria central", Some(UUID.randomUUID())),
        setCategoryRule("cafeteria central", UUID.randomUUID()),
        ignoreRule("cafe")
      )
      RowRuleVerdict.verdict(rules, "CAFETERIA CENTRAL") must be(RowRuleVerdict.Ignore)
    }

    "skip patterns shorter than 3 normalized chars" in {
      val rules = List(ignoreRule("m-e"), setMerchantRule("..ab", Some(UUID.randomUUID())))
      RowRuleVerdict.verdict(rules, "ME AB STORE") must be(RowRuleVerdict.NoRule)
    }
  }

  "ruleMerchantId" should {
    "return the merchant of a winning set_merchant rule" in {
      val mid = UUID.randomUUID()
      RowRuleVerdict.ruleMerchantId(List(setMerchantRule("mercadona", Some(mid))), "MERCADONA STORE") must be(Some(mid))
    }

    "be None when an ignore rule wins" in {
      val rules = List(setMerchantRule("mercadona", Some(UUID.randomUUID())), ignoreRule("mercadona store"))
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
      val rules = List(setCategoryRule("mercadona store", UUID.randomUUID()), setMerchantRule("mercadona", None))
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
