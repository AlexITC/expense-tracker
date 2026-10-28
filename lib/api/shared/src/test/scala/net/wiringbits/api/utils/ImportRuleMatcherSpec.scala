package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class ImportRuleMatcherSpec extends AnyWordSpec with Matchers {

  private def setMerchantRule(id: UUID, pattern: String, merchantId: UUID) =
    ImportRuleMatcher.Rule(id, ImportRuleAction.SetMerchant(merchantId), pattern)
  private def ignoreRule(id: UUID, pattern: String) =
    ImportRuleMatcher.Rule(id, ImportRuleAction.Ignore, pattern)
  private def setCategoryRule(id: UUID, pattern: String, categoryId: UUID) =
    ImportRuleMatcher.Rule(id, ImportRuleAction.SetCategory(categoryId), pattern)
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

    "prefer set_merchant over a longer set_category pattern" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "CAFE", mid)
      val category = setCategoryRule(UUID.randomUUID(), "CAFETERIA", UUID.randomUUID())
      ImportRuleMatcher.findRowWinner(List(category, merchant), "CAFETERIA CENTRAL") must be(Some(merchant))
    }

    "return the set_category rule when it is the only match" in {
      val category = setCategoryRule(UUID.randomUUID(), "cafeteria", UUID.randomUUID())
      ImportRuleMatcher.findRowWinner(List(category), "CAFETERIA CENTRAL") must be(Some(category))
    }

    "prefer the longer pattern between two set_category rules" in {
      val short = setCategoryRule(UUID.randomUUID(), "cafe", UUID.randomUUID())
      val long = setCategoryRule(UUID.randomUUID(), "cafeteria", UUID.randomUUID())
      ImportRuleMatcher.findRowWinner(List(long, short), "CAFETERIA CENTRAL") must be(Some(long))
    }

    "prefer ignore over set_merchant and set_category" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "cafeteria central", mid)
      val category = setCategoryRule(UUID.randomUUID(), "cafeteria central", UUID.randomUUID())
      val ignore = ignoreRule(UUID.randomUUID(), "cafe")
      ImportRuleMatcher.findRowWinner(List(merchant, category, ignore), "CAFETERIA CENTRAL") must be(Some(ignore))
    }
  }

  "ImportRuleMatcher.findReceiptWinner" should {
    "only apply set_merchant rules" in {
      val ignore = ignoreRule(UUID.randomUUID(), "amzn")
      ImportRuleMatcher.findReceiptWinner(List(ignore), "AMZN charge") must be(None)
    }

    "never apply set_category rules" in {
      val category = setCategoryRule(UUID.randomUUID(), "amzn", UUID.randomUUID())
      ImportRuleMatcher.findReceiptWinner(List(category), "AMZN receipt") must be(None)
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

  "effectiveRules" should {
    def merchant(name: String) = UUID.randomUUID() -> name

    "match a merchant name with no explicit rules" in {
      val oxxo = merchant("Oxxo")
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(oxxo))
      RowRuleVerdict.verdict(rules, "OXXO STORE 123") must be(RowRuleVerdict.SetMerchant(oxxo._1))
    }

    "prefer the longer merchant name over a shorter merchant name" in {
      val cafe = merchant("Cafe")
      val dindurra = merchant("Cafe Dindurra")
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(cafe, dindurra))
      RowRuleVerdict.verdict(rules, "CAFE DINDURRA MADRID") must be(RowRuleVerdict.SetMerchant(dindurra._1))
    }

    "prefer the longer merchant name over a shorter explicit alias" in {
      val dindurra = merchant("Cafe Dindurra")
      val alias = setMerchantRule(UUID.randomUUID(), "CAFE", UUID.randomUUID())
      val rules = ImportRuleMatcher.effectiveRules(List(alias), List(dindurra))
      RowRuleVerdict.verdict(rules, "CAFE DINDURRA MADRID") must be(RowRuleVerdict.SetMerchant(dindurra._1))
    }

    "let an explicit ignore rule win over a matching merchant name" in {
      val ignore = ignoreRule(UUID.randomUUID(), "oxxo")
      val rules = ImportRuleMatcher.effectiveRules(List(ignore), List(merchant("Oxxo Store")))
      ImportRuleMatcher.findRowWinner(rules, "OXXO STORE 123") must be(Some(ignore))
      RowRuleVerdict.verdict(rules, "OXXO STORE 123") must be(RowRuleVerdict.Ignore)
    }

    "never match a merchant whose normalized name is shorter than 3 characters" in {
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(merchant("A&B")))
      RowRuleVerdict.verdict(rules, "AB SUPPLIES A&B") must be(RowRuleVerdict.NoRule)
    }

    "follow a merchant rename without any rule row" in {
      val id = UUID.randomUUID()
      val before = ImportRuleMatcher.effectiveRules(Nil, List(id -> "Old Bakery"))
      RowRuleVerdict.verdict(before, "OLD BAKERY 42") must be(RowRuleVerdict.SetMerchant(id))

      val after = ImportRuleMatcher.effectiveRules(Nil, List(id -> "Sunrise Bakery"))
      RowRuleVerdict.verdict(after, "OLD BAKERY 42") must be(RowRuleVerdict.NoRule)
      RowRuleVerdict.verdict(after, "SUNRISE BAKERY 42") must be(RowRuleVerdict.SetMerchant(id))
    }

    "let an explicit set_merchant rule with the same normalized pattern decide" in {
      val oxxo = merchant("Oxxo")
      val other = UUID.randomUUID()
      // Smallest possible UUID: the explicit rule would lose a tiebreak, so only the de-duplication explains the result.
      val toOther = setMerchantRule(UUID.fromString("00000000-0000-4000-8000-000000000001"), "OXXO", other)
      val redirected = ImportRuleMatcher.effectiveRules(List(toOther), List(oxxo))
      RowRuleVerdict.verdict(redirected, "OXXO STORE 123") must be(RowRuleVerdict.SetMerchant(other))
    }

    "skip the virtual rule when an explicit set_merchant rule has the same normalized pattern" in {
      val oxxo = merchant("Oxxo")
      val toSame = setMerchantRule(UUID.randomUUID(), "o.x.x.o", oxxo._1)
      ImportRuleMatcher.effectiveRules(List(toSame), List(oxxo)) must be(List(toSame))
    }

    "resolve a receipt vendor to a merchant by name" in {
      val mercadona = merchant("Mercadona")
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(merchant("Lidl"), mercadona))
      ImportRuleMatcher.findReceiptWinner(rules, "MERCADONA, S.A.").flatMap(_.action.merchantIdOpt) must be(
        Some(mercadona._1)
      )
    }
  }
}
