package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class ImportRuleMatcherSpec extends AnyWordSpec with Matchers {

  private def setMerchantRule(id: UUID, pattern: String, merchantId: UUID) =
    ImportRuleMatcher.Rule(id, ImportRuleAction.SetMerchant, pattern, Some(merchantId))
  private def ignoreRule(id: UUID, pattern: String) =
    ImportRuleMatcher.Rule(id, ImportRuleAction.Ignore, pattern, None)
  private val mid = UUID.randomUUID()

  "ImportRuleMatcher.normalize" should {
    "lowercase and strip non-alphanumeric" in {
      ImportRuleMatcher.normalize("Mercadona, S.A.") must be("mercadonasa")
      ImportRuleMatcher.normalize("AMZN*MARKETPLACE") must be("amznmarketplace")
      ImportRuleMatcher.normalize("  ! ") must be("")
    }
  }

  "ImportRuleMatcher.matches" should {
    "match via normalized substring (strips punctuation/spaces)" in {
      val r = setMerchantRule(UUID.randomUUID(), "Mercadona, S.A.", mid)
      ImportRuleMatcher.matches(r, "MERCADONA-S.A. STORE") must be(true)
    }

    "be case-insensitive and punctuation-insensitive" in {
      val r = setMerchantRule(UUID.randomUUID(), "Amazon", mid)
      ImportRuleMatcher.matches(r, "AMAZON PRIME") must be(true)
    }

    "return false when normalized pattern is not present" in {
      val r = setMerchantRule(UUID.randomUUID(), "walmart", mid)
      ImportRuleMatcher.matches(r, "AMAZON PRIME") must be(false)
    }
  }

  "ImportRuleMatcher.findRowWinner" should {
    "return None for an empty list" in {
      ImportRuleMatcher.findRowWinner(Nil, "anything") must be(None)
    }

    "return None when normalized pattern is shorter than 3 characters" in {
      val r = setMerchantRule(UUID.randomUUID(), "  ! ", mid)
      ImportRuleMatcher.findRowWinner(List(r), "anything") must be(None)
    }

    "return the matching rule" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(r), "AMZN*MARKETPLACE") must be(Some(r))
    }

    "prefer the longer normalized pattern" in {
      val short = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      val long = setMerchantRule(UUID.randomUUID(), "amzn*marketplace", mid)
      ImportRuleMatcher.findRowWinner(List(short, long), "amzn*marketplace store") must be(Some(long))
    }

    "tiebreak by lexicographically larger UUID (most recent UUIDv7)" in {
      val older = setMerchantRule(UUID.fromString("018e0000-0000-7000-8000-000000000001"), "amzn", mid)
      val newer = setMerchantRule(UUID.fromString("018e9999-9999-7000-8000-000000000002"), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(older, newer), "AMZN store") must be(Some(newer))
    }

    "prefer ignore rule over set_merchant when both match" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      val ignore = ignoreRule(UUID.randomUUID(), "amzn")
      ImportRuleMatcher.findRowWinner(List(merchant, ignore), "AMZN charge") must be(Some(ignore))
    }

    "return set_merchant rule when no ignore rule matches" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(merchant), "AMZN charge") must be(Some(merchant))
    }
  }

  "ImportRuleMatcher.findReceiptWinner" should {
    "only apply set_merchant rules" in {
      val ignore = ignoreRule(UUID.randomUUID(), "amzn")
      ImportRuleMatcher.findReceiptWinner(List(ignore), "AMZN charge") must be(None)
    }

    "return set_merchant rule for matching receipt" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findReceiptWinner(List(merchant), "AMZN receipt") must be(Some(merchant))
    }

    "match when pattern and target differ only in punctuation (normalized)" in {
      val r = setMerchantRule(UUID.randomUUID(), "Mercadona, S.A.", mid)
      ImportRuleMatcher.findReceiptWinner(List(r), "MERCADONA-S.A.") must be(Some(r))
    }
  }
}
