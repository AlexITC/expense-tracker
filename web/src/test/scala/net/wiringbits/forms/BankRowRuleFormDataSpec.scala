package net.wiringbits.forms

import net.wiringbits.api.models.merchants.{GetImportRules, ImportRuleAction, ImportRuleActionKind}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.{ExistingOrNewValue, ExpRuleAmount, ExpRulePattern}
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class BankRowRuleFormDataSpec extends AnyWordSpec {

  private val merchantSelection =
    ExistingOrNewValue[MerchantName, UUID](value = MerchantName.trusted("Store"), id = Some(UUID.randomUUID()))
  private val categorySelection =
    ExistingOrNewValue[CategoryName, UUID](value = CategoryName.trusted("Food"), id = Some(UUID.randomUUID()))

  private val setMerchant =
    BankRowRuleFormData
      .initial(patternSeed = "grocery store", amountSeed = "", descriptionSeed = "note")
      .asInstanceOf[BankRowRuleFormData.SetMerchant]

  "SetMerchant" should {
    "not be submittable until a merchant is chosen" in {
      setMerchant.isValid must be(false)
      setMerchant.submitRequest must be(None)
    }

    "be submittable once a merchant is chosen" in {
      val form =
        setMerchant.copy(merchant = setMerchant.merchant.updated(ValidationResult.Valid("Store", merchantSelection)))
      form.isValid must be(true)
      form.submitRequest.isDefined must be(true)
    }

    "keep the category optional" in {
      val form =
        setMerchant.copy(merchant = setMerchant.merchant.updated(ValidationResult.Valid("Store", merchantSelection)))
      form.submitRequest must be(
        Some(
          BankRowRuleFormData.Resolved
            .SetMerchant(
              pattern = "grocery store",
              amount = None,
              description = Some("note"),
              merchant = merchantSelection,
              category = None
            )
        )
      )
    }
  }

  "SetCategory" should {
    "require a category" in {
      val form = BankRowRuleFormData.changeKind(setMerchant, ImportRuleActionKind.SetCategory)
      form.isValid must be(false)
      form.submitRequest must be(None)
    }
  }

  "Ignore" should {
    "be submittable with only a valid pattern" in {
      val form = BankRowRuleFormData.changeKind(setMerchant, ImportRuleActionKind.Ignore)
      form.submitRequest must be(Some(BankRowRuleFormData.Resolved.Ignore(pattern = "grocery store", amount = None)))
    }
  }

  "changeKind" should {
    "carry pattern, description and the chosen category to SetCategory" in {
      val withCategory =
        setMerchant.copy(category =
          setMerchant.category.updated(ValidationResult.Valid("Food", Some(categorySelection)))
        )
      BankRowRuleFormData.changeKind(withCategory, ImportRuleActionKind.SetCategory) match {
        case fd: BankRowRuleFormData.SetCategory =>
          fd.pattern.inputValue must be("grocery store")
          fd.description.inputValue must be("note")
          fd.category.valueOpt must be(Some(categorySelection))
        case other => fail(s"expected SetCategory, got $other")
      }
    }
  }

  private def storedRule(
      action: ImportRuleAction,
      pattern: String = "grocery store",
      amount: Option[BigDecimal] = None,
      merchantName: Option[String] = None,
      categoryName: Option[String] = None
  ): GetImportRules.Rule =
    GetImportRules.Rule(
      id = UUID.randomUUID(),
      action = action,
      pattern = pattern,
      amount = amount,
      merchantName = merchantName,
      categoryName = categoryName,
      autoCreated = false,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH
    )

  "fromRule" should {
    "seed a set_merchant rule with its merchant, override category, amount and description" in {
      val mid = UUID.randomUUID()
      val cid = UUID.randomUUID()
      val r = storedRule(
        ImportRuleAction.SetMerchant(mid, categoryId = Some(cid), description = Some("Books")),
        amount = Some(BigDecimal("-12.50")),
        merchantName = Some("Store"),
        categoryName = Some("Food")
      )
      BankRowRuleFormData.fromRule(r).submitRequest match {
        case Some(BankRowRuleFormData.Resolved.SetMerchant(pattern, amount, description, merchant, category)) =>
          pattern must be("grocery store")
          // A stored negative amount seeds as its positive magnitude.
          amount must be(Some(BigDecimal("12.50")))
          description must be(Some("Books"))
          (merchant.value.string, merchant.id) must be(("Store", Some(mid)))
          category.map(c => (c.value.string, c.id)) must be(Some(("Food", Some(cid))))
        case other => fail(s"expected SetMerchant, got $other")
      }
    }

    "seed a set_merchant rule without an override as no category" in {
      val r = storedRule(
        ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None),
        merchantName = Some("Store")
      )
      BankRowRuleFormData.fromRule(r) match {
        case fd: BankRowRuleFormData.SetMerchant =>
          fd.category.valueOpt must be(Some(None))
          fd.description.valueOpt must be(Some(None))
          fd.amount.valueOpt must be(Some(None))
        case other => fail(s"expected SetMerchant, got $other")
      }
    }

    "seed the target category of a set_category rule" in {
      val cid = UUID.randomUUID()
      val r = storedRule(ImportRuleAction.SetCategory(cid, description = None), categoryName = Some("Food"))
      BankRowRuleFormData.fromRule(r).submitRequest match {
        case Some(BankRowRuleFormData.Resolved.SetCategory(_, None, None, category)) =>
          (category.value.string, category.id) must be(("Food", Some(cid)))
        case other => fail(s"expected SetCategory, got $other")
      }
    }

    "seed an ignore rule" in {
      BankRowRuleFormData.fromRule(storedRule(ImportRuleAction.Ignore)).submitRequest must be(
        Some(BankRowRuleFormData.Resolved.Ignore(pattern = "grocery store", amount = None))
      )
    }

    "not be submittable when the stored pattern is invalid" in {
      val tooShort = storedRule(ImportRuleAction.Ignore, pattern = "ab")
      val tooLong = storedRule(ImportRuleAction.Ignore, pattern = "x" * (ImportRuleMatcher.MaxPatternLength + 1))
      Seq(tooShort, tooLong).foreach { r =>
        val form = BankRowRuleFormData.fromRule(r)
        form.pattern.inputValue must be(r.pattern)
        form.submitRequest must be(None)
      }
    }

    "not be submittable when the stored merchant name is over the limit" in {
      val r = storedRule(
        ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None),
        merchantName = Some("X" * (MerchantName.MaxLength + 1))
      )
      BankRowRuleFormData.fromRule(r).submitRequest must be(None)
    }

    "mark an over-limit stored override category as invalid" in {
      val r = storedRule(
        ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = Some(UUID.randomUUID()), description = None),
        merchantName = Some("Store"),
        categoryName = Some("X" * (CategoryName.MaxLength + 1))
      )
      BankRowRuleFormData.fromRule(r) match {
        case fd: BankRowRuleFormData.SetMerchant =>
          fd.category.value.exists(_.hasError) must be(true)
          fd.submitRequest must be(None)
        case other => fail(s"expected SetMerchant, got $other")
      }
    }

    "mark an over-limit stored target category as invalid" in {
      val r = storedRule(
        ImportRuleAction.SetCategory(UUID.randomUUID(), description = None),
        categoryName = Some("X" * (CategoryName.MaxLength + 1))
      )
      BankRowRuleFormData.fromRule(r).submitRequest must be(None)
    }

    "not be submittable when a set_category rule has no category name" in {
      val r = storedRule(ImportRuleAction.SetCategory(UUID.randomUUID(), description = None), categoryName = None)
      BankRowRuleFormData.fromRule(r).submitRequest must be(None)
    }

    "not be submittable when a set_merchant rule has no merchant name" in {
      val r = storedRule(
        ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None),
        merchantName = None
      )
      BankRowRuleFormData.fromRule(r).submitRequest must be(None)
    }

    "seed no override category when the stored override has no name" in {
      val r = storedRule(
        ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = Some(UUID.randomUUID()), description = None),
        merchantName = Some("Store"),
        categoryName = None
      )
      BankRowRuleFormData.fromRule(r) match {
        case fd: BankRowRuleFormData.SetMerchant => fd.category.value must be(Some(ValidationResult.Valid("", None)))
        case other => fail(s"expected SetMerchant, got $other")
      }
    }
  }

  "empty" should {
    "show no error until the pattern is touched" in {
      BankRowRuleFormData.empty.pattern.value must be(None)
      BankRowRuleFormData.empty.isValid must be(false)
    }

    "not be submittable until a pattern and a merchant are set" in {
      val empty = BankRowRuleFormData.empty.asInstanceOf[BankRowRuleFormData.SetMerchant]
      val withPattern = empty.copy(pattern = empty.pattern.updated(ExpRulePattern.validate("grocery store")))
      withPattern.submitRequest must be(None)
      val complete =
        withPattern.copy(merchant = withPattern.merchant.updated(ValidationResult.Valid("Store", merchantSelection)))
      complete.submitRequest.isDefined must be(true)
    }
  }

  "amount" should {
    "reject text that is not a number and non-positive amounts" in {
      val ignore = BankRowRuleFormData.changeKind(setMerchant, ImportRuleActionKind.Ignore)
      Seq("abc", "12,50", "0", "-9.99", "1.234").foreach { text =>
        ignore.withAmount(ignore.amount.updated(ExpRuleAmount.optionalValidator(text))).submitRequest must be(None)
      }
    }

    "accept a positive amount" in {
      val ignore = BankRowRuleFormData.changeKind(setMerchant, ImportRuleActionKind.Ignore)
      ignore.withAmount(ignore.amount.updated(ExpRuleAmount.optionalValidator(" 9.99 "))).submitRequest must be(
        Some(BankRowRuleFormData.Resolved.Ignore(pattern = "grocery store", amount = Some(BigDecimal("9.99"))))
      )
    }
  }
}
