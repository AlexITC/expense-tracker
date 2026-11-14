package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.api.models.merchants.{CreateImportRule, ImportRuleAction, ImportRuleActionKind}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class BankRowRuleFormSpec extends AnyWordSpec {

  private val merchantId = UUID.randomUUID()
  private val categoryId = UUID.randomUUID()

  private def build(
      kind: ImportRuleActionKind = ImportRuleActionKind.SetMerchant,
      pattern: String = "STORE 123",
      amountText: String = "-12.50",
      merchantId: Option[UUID] = Some(merchantId),
      categoryId: Option[UUID] = None,
      description: String = ""
  ): Either[String, CreateImportRule.Request] =
    BankRowRuleForm.buildRequest(
      kind = kind,
      pattern = pattern,
      amountText = amountText,
      merchantId = merchantId,
      categoryId = categoryId,
      description = description
    )

  "buildRequest" should {
    "parse the prefilled signed row amount" in {
      build().map(_.amount) mustBe Right(Some(BigDecimal("-12.50")))
    }

    "treat a blank amount as a pattern-only rule" in {
      build(amountText = "  ").map(_.amount) mustBe Right(None)
    }

    "treat a blank description as None" in {
      build(description = "   ").map(_.action) mustBe Right(
        ImportRuleAction.SetMerchant(merchantId, categoryId = None, description = None)
      )
    }

    "carry the chosen category as the set-merchant override along with the description" in {
      build(categoryId = Some(categoryId), description = " Lunch ").map(_.action) mustBe Right(
        ImportRuleAction.SetMerchant(merchantId, categoryId = Some(categoryId), description = Some("Lunch"))
      )
    }

    "build a set-category action with its description" in {
      build(
        kind = ImportRuleActionKind.SetCategory,
        merchantId = None,
        categoryId = Some(categoryId),
        description = "x"
      )
        .map(_.action) mustBe Right(ImportRuleAction.SetCategory(categoryId, description = Some("x")))
    }

    "drop the description for ignore rules" in {
      build(kind = ImportRuleActionKind.Ignore, merchantId = None, description = "anything")
        .map(_.action) mustBe Right(ImportRuleAction.Ignore)
    }

    "trim the pattern" in {
      build(pattern = "  STORE 123 ").map(_.pattern) mustBe Right("STORE 123")
    }

    "reject an unparseable amount" in {
      build(amountText = "abc") mustBe Left("Amount must be a number, e.g. -12.50")
    }

    "require a merchant for set-merchant" in {
      build(merchantId = None) mustBe Left("Merchant is required")
    }

    "require a category for set-category" in {
      build(kind = ImportRuleActionKind.SetCategory, merchantId = None, categoryId = None) mustBe
        Left("Category is required")
    }
  }
}
