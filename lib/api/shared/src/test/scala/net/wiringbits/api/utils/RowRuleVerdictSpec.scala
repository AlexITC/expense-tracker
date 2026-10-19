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
  }
}
