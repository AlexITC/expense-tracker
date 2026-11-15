package net.wiringbits.forms

import net.wiringbits.api.models.merchants.ImportRuleActionKind
import net.wiringbits.common.models.{CategoryName, MerchantName}
import net.wiringbits.ui.forms.ExistingOrNewValue
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

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
}
