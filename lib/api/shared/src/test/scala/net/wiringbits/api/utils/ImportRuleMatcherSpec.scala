package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class ImportRuleMatcherSpec extends AnyWordSpec with Matchers {

  private def setMerchantRule(id: UUID, pattern: String, merchantId: UUID, amount: Option[BigDecimal] = None) =
    ImportRuleMatcher.Rule(
      id,
      ImportRuleAction.SetMerchant(merchantId, categoryId = None, description = None),
      pattern,
      amount
    )
  private def ignoreRule(id: UUID, pattern: String, amount: Option[BigDecimal] = None) =
    ImportRuleMatcher.Rule(id, ImportRuleAction.Ignore, pattern, amount)
  private def setCategoryRule(id: UUID, pattern: String, categoryId: UUID, amount: Option[BigDecimal] = None) =
    ImportRuleMatcher.Rule(id, ImportRuleAction.SetCategory(categoryId, description = None), pattern, amount)
  private val mid = UUID.randomUUID()
  // Rules without an amount ignore the row amount; this is any row amount for those tests.
  private val anyAmount = BigDecimal("-10.00")

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
      ImportRuleMatcher.findRowWinner(Nil, "anything", anyAmount) must be(None)
    }

    "return None when normalized pattern is shorter than 3 characters" in {
      val r = setMerchantRule(UUID.randomUUID(), "  ! ", mid)
      ImportRuleMatcher.findRowWinner(List(r), "anything", anyAmount) must be(None)
    }

    "return the matching rule" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(r), "AMZN*MARKETPLACE", anyAmount) must be(Some(r))
    }

    "prefer the longer normalized pattern" in {
      val short = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      val long = setMerchantRule(UUID.randomUUID(), "amzn*marketplace", mid)
      ImportRuleMatcher.findRowWinner(List(short, long), "amzn*marketplace store", anyAmount) must be(Some(long))
    }

    "tiebreak by lexicographically larger UUID (most recent UUIDv7)" in {
      val older = setMerchantRule(UUID.fromString("018e0000-0000-7000-8000-000000000001"), "amzn", mid)
      val newer = setMerchantRule(UUID.fromString("018e9999-9999-7000-8000-000000000002"), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(older, newer), "AMZN store", anyAmount) must be(Some(newer))
    }

    "prefer ignore rule over set_merchant when both match" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      val ignore = ignoreRule(UUID.randomUUID(), "amzn")
      ImportRuleMatcher.findRowWinner(List(merchant, ignore), "AMZN charge", anyAmount) must be(Some(ignore))
    }

    "return set_merchant rule when no ignore rule matches" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(merchant), "AMZN charge", anyAmount) must be(Some(merchant))
    }

    "prefer set_merchant over a longer set_category pattern" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "CAFE", mid)
      val category = setCategoryRule(UUID.randomUUID(), "CAFETERIA", UUID.randomUUID())
      ImportRuleMatcher.findRowWinner(List(category, merchant), "CAFETERIA CENTRAL", anyAmount) must be(Some(merchant))
    }

    "return the set_category rule when it is the only match" in {
      val category = setCategoryRule(UUID.randomUUID(), "cafeteria", UUID.randomUUID())
      ImportRuleMatcher.findRowWinner(List(category), "CAFETERIA CENTRAL", anyAmount) must be(Some(category))
    }

    "prefer the longer pattern between two set_category rules" in {
      val short = setCategoryRule(UUID.randomUUID(), "cafe", UUID.randomUUID())
      val long = setCategoryRule(UUID.randomUUID(), "cafeteria", UUID.randomUUID())
      ImportRuleMatcher.findRowWinner(List(long, short), "CAFETERIA CENTRAL", anyAmount) must be(Some(long))
    }

    "prefer ignore over set_merchant and set_category" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "cafeteria central", mid)
      val category = setCategoryRule(UUID.randomUUID(), "cafeteria central", UUID.randomUUID())
      val ignore = ignoreRule(UUID.randomUUID(), "cafe")
      ImportRuleMatcher.findRowWinner(List(merchant, category, ignore), "CAFETERIA CENTRAL", anyAmount) must be(
        Some(ignore)
      )
    }
  }

  "findRowWinner with amounts" should {
    val charge = BigDecimal("-12.50")

    "match a rule with an amount only on the exact amount" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid, amount = Some(charge))
      ImportRuleMatcher.findRowWinner(List(r), "AMZN charge", BigDecimal("-12.5")) must be(Some(r))
      ImportRuleMatcher.findRowWinner(List(r), "AMZN charge", BigDecimal("-12.51")) must be(None)
    }

    "not match when only the sign differs" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid, amount = Some(charge))
      ImportRuleMatcher.findRowWinner(List(r), "AMZN refund", charge.abs) must be(None)
    }

    "not match on the amount alone" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid, amount = Some(charge))
      ImportRuleMatcher.findRowWinner(List(r), "OTHER STORE", charge) must be(None)
    }

    "match a rule without an amount on any amount" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(r), "AMZN charge", charge) must be(Some(r))
      ImportRuleMatcher.findRowWinner(List(r), "AMZN refund", charge.abs) must be(Some(r))
    }

    "prefer pattern plus amount over a longer pattern-only rule in the same tier" in {
      val withAmount = setMerchantRule(UUID.randomUUID(), "amzn", UUID.randomUUID(), amount = Some(charge))
      val longer = setMerchantRule(UUID.randomUUID(), "amzn marketplace", mid)
      ImportRuleMatcher.findRowWinner(List(longer, withAmount), "AMZN MARKETPLACE", charge) must be(Some(withAmount))
      ImportRuleMatcher.findRowWinner(List(longer, withAmount), "AMZN MARKETPLACE", BigDecimal(-1)) must be(
        Some(longer)
      )
    }

    "keep the tiers above the amount criterion" in {
      val merchantWithAmount = setMerchantRule(UUID.randomUUID(), "amzn", mid, amount = Some(charge))
      val ignore = ignoreRule(UUID.randomUUID(), "amzn")
      ImportRuleMatcher.findRowWinner(List(merchantWithAmount, ignore), "AMZN charge", charge) must be(Some(ignore))

      val categoryWithAmount = setCategoryRule(UUID.randomUUID(), "amzn", UUID.randomUUID(), amount = Some(charge))
      val merchant = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findRowWinner(List(categoryWithAmount, merchant), "AMZN charge", charge) must be(
        Some(merchant)
      )
    }
  }

  "findReceiptWinner with amounts" should {
    "compare the positive receipt total against the rule amount as a charge" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid, amount = Some(BigDecimal("-12.50")))
      ImportRuleMatcher.findReceiptWinner(List(r), "AMZN receipt", Some(BigDecimal("12.50"))) must be(Some(r))
      ImportRuleMatcher.findReceiptWinner(List(r), "AMZN receipt", Some(BigDecimal("13.00"))) must be(None)
    }

    "not match a rule with an amount when the receipt total is unknown" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid, amount = Some(BigDecimal("-12.50")))
      ImportRuleMatcher.findReceiptWinner(List(r), "AMZN receipt", None) must be(None)
    }

    "treat a receipt total of 0 as unknown" in {
      val withAmount = setMerchantRule(UUID.randomUUID(), "amzn", mid, amount = Some(BigDecimal("0")))
      val plain = setMerchantRule(UUID.randomUUID(), "amzn", UUID.randomUUID())
      ImportRuleMatcher.findReceiptWinner(List(withAmount), "AMZN receipt", Some(BigDecimal("0.00"))) must be(None)
      ImportRuleMatcher.findReceiptWinner(List(plain), "AMZN receipt", Some(BigDecimal("0.00"))) must be(Some(plain))
    }

    "match a rule without an amount with or without a receipt total" in {
      val r = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findReceiptWinner(List(r), "AMZN receipt", None) must be(Some(r))
      ImportRuleMatcher.findReceiptWinner(List(r), "AMZN receipt", Some(BigDecimal("5"))) must be(Some(r))
    }
  }

  "validate" should {
    def valid(
        pattern: String = "AMZN",
        amount: Option[BigDecimal] = None,
        action: ImportRuleAction = ImportRuleAction.Ignore
    ) = ImportRuleMatcher.validate(pattern, amount, action)
    def withDescription(description: String) =
      ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = Some(description))

    "accept valid rules" in {
      valid() must be(Right(()))
      valid(amount = Some(BigDecimal("-12.50"))) must be(Right(()))
      valid(amount = Some(BigDecimal("9999999999.99"))) must be(Right(()))
      valid(action = withDescription("x" * ImportRuleMatcher.MaxDescriptionLength)) must be(Right(()))
    }

    "reject a short pattern" in {
      valid(pattern = "a-b") must be(Left("Rule pattern must be at least 3 characters"))
    }

    "reject a pattern longer than the limit" in {
      valid(pattern = "x" * ImportRuleMatcher.MaxPatternLength) must be(Right(()))
      valid(pattern = "x" * (ImportRuleMatcher.MaxPatternLength + 1)) must be(
        Left("Rule pattern must be 255 characters or less")
      )
    }

    "reject a zero amount" in {
      valid(amount = Some(BigDecimal("0.00"))) must be(Left("Rule amount must not be zero"))
    }

    "reject an amount with more than 2 decimals" in {
      valid(amount = Some(BigDecimal("1.234"))) must be(Left("Rule amount must have at most 2 decimal places"))
    }

    "reject an amount that does not fit the column" in {
      valid(amount = Some(BigDecimal("-10000000000"))) must be(
        Left("Rule amount must have at most 10 integer digits")
      )
    }

    "reject a blank description" in {
      valid(action = withDescription("   ")) must be(Left("Rule description must not be blank"))
    }

    "reject a description longer than the limit" in {
      valid(action = withDescription("x" * (ImportRuleMatcher.MaxDescriptionLength + 1))) must be(
        Left("Rule description must be 255 characters or less")
      )
    }
  }

  "ImportRuleMatcher.findReceiptWinner" should {
    "only apply set_merchant rules" in {
      val ignore = ignoreRule(UUID.randomUUID(), "amzn")
      ImportRuleMatcher.findReceiptWinner(List(ignore), "AMZN charge", None) must be(None)
    }

    "never apply set_category rules" in {
      val category = setCategoryRule(UUID.randomUUID(), "amzn", UUID.randomUUID())
      ImportRuleMatcher.findReceiptWinner(List(category), "AMZN receipt", None) must be(None)
    }

    "return set_merchant rule for matching receipt" in {
      val merchant = setMerchantRule(UUID.randomUUID(), "amzn", mid)
      ImportRuleMatcher.findReceiptWinner(List(merchant), "AMZN receipt", None) must be(Some(merchant))
    }

    "match when pattern and target differ only in punctuation (normalized)" in {
      val r = setMerchantRule(UUID.randomUUID(), "Mercadona, S.A.", mid)
      ImportRuleMatcher.findReceiptWinner(List(r), "MERCADONA-S.A.", None) must be(Some(r))
    }
  }

  "effectiveRules" should {
    def merchant(name: String) = UUID.randomUUID() -> name

    "match a merchant name with no explicit rules" in {
      val oxxo = merchant("Oxxo")
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(oxxo))
      RowRuleVerdict.verdict(rules, "OXXO STORE 123", anyAmount) must be(
        RowRuleVerdict.SetMerchant(oxxo._1, categoryId = None, description = None)
      )
    }

    "prefer the longer merchant name over a shorter merchant name" in {
      val cafe = merchant("Cafe")
      val dindurra = merchant("Cafe Dindurra")
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(cafe, dindurra))
      RowRuleVerdict.verdict(rules, "CAFE DINDURRA MADRID", anyAmount) must be(
        RowRuleVerdict.SetMerchant(dindurra._1, categoryId = None, description = None)
      )
    }

    "prefer the longer merchant name over a shorter explicit alias" in {
      val dindurra = merchant("Cafe Dindurra")
      val alias = setMerchantRule(UUID.randomUUID(), "CAFE", UUID.randomUUID())
      val rules = ImportRuleMatcher.effectiveRules(List(alias), List(dindurra))
      RowRuleVerdict.verdict(rules, "CAFE DINDURRA MADRID", anyAmount) must be(
        RowRuleVerdict.SetMerchant(dindurra._1, categoryId = None, description = None)
      )
    }

    "let an explicit ignore rule win over a matching merchant name" in {
      val ignore = ignoreRule(UUID.randomUUID(), "oxxo")
      val rules = ImportRuleMatcher.effectiveRules(List(ignore), List(merchant("Oxxo Store")))
      ImportRuleMatcher.findRowWinner(rules, "OXXO STORE 123", anyAmount) must be(Some(ignore))
      RowRuleVerdict.verdict(rules, "OXXO STORE 123", anyAmount) must be(RowRuleVerdict.Ignore)
    }

    "never match a merchant whose normalized name is shorter than 3 characters" in {
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(merchant("A&B")))
      RowRuleVerdict.verdict(rules, "AB SUPPLIES A&B", anyAmount) must be(RowRuleVerdict.NoRule)
    }

    "follow a merchant rename without any rule row" in {
      val id = UUID.randomUUID()
      val before = ImportRuleMatcher.effectiveRules(Nil, List(id -> "Old Bakery"))
      RowRuleVerdict.verdict(before, "OLD BAKERY 42", anyAmount) must be(
        RowRuleVerdict.SetMerchant(id, categoryId = None, description = None)
      )

      val after = ImportRuleMatcher.effectiveRules(Nil, List(id -> "Sunrise Bakery"))
      RowRuleVerdict.verdict(after, "OLD BAKERY 42", anyAmount) must be(RowRuleVerdict.NoRule)
      RowRuleVerdict.verdict(after, "SUNRISE BAKERY 42", anyAmount) must be(
        RowRuleVerdict.SetMerchant(id, categoryId = None, description = None)
      )
    }

    "let an explicit set_merchant rule with the same normalized pattern decide" in {
      val oxxo = merchant("Oxxo")
      val other = UUID.randomUUID()
      // Smallest possible UUID: the explicit rule would lose a tiebreak, so only the de-duplication explains the result.
      val toOther = setMerchantRule(UUID.fromString("00000000-0000-4000-8000-000000000001"), "OXXO", other)
      val redirected = ImportRuleMatcher.effectiveRules(List(toOther), List(oxxo))
      RowRuleVerdict.verdict(redirected, "OXXO STORE 123", anyAmount) must be(
        RowRuleVerdict.SetMerchant(other, categoryId = None, description = None)
      )
    }

    "skip the virtual rule when an explicit set_merchant rule has the same normalized pattern" in {
      val oxxo = merchant("Oxxo")
      val toSame = setMerchantRule(UUID.randomUUID(), "o.x.x.o", oxxo._1)
      ImportRuleMatcher.effectiveRules(List(toSame), List(oxxo)) must be(List(toSame))
    }

    "keep the virtual rule when the explicit set_merchant rule with the same pattern has an amount" in {
      val oxxo = merchant("Oxxo")
      val other = UUID.randomUUID()
      val withAmount = setMerchantRule(UUID.randomUUID(), "OXXO", other, amount = Some(BigDecimal("-5")))
      val rules = ImportRuleMatcher.effectiveRules(List(withAmount), List(oxxo))
      RowRuleVerdict.verdict(rules, "OXXO STORE 123", BigDecimal("-5")) must be(
        RowRuleVerdict.SetMerchant(other, categoryId = None, description = None)
      )
      RowRuleVerdict.verdict(rules, "OXXO STORE 123", BigDecimal("-7")) must be(
        RowRuleVerdict.SetMerchant(oxxo._1, categoryId = None, description = None)
      )
    }

    "resolve a receipt vendor to a merchant by name" in {
      val mercadona = merchant("Mercadona")
      val rules = ImportRuleMatcher.effectiveRules(Nil, List(merchant("Lidl"), mercadona))
      ImportRuleMatcher.findReceiptWinner(rules, "MERCADONA, S.A.", None).flatMap(_.action.merchantIdOpt) must be(
        Some(mercadona._1)
      )
    }
  }
}
