package net.wiringbits.ui.forms

import net.wiringbits.api.utils.BankStatementDraftLimits
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class ExpDraftFieldsSpec extends AnyWordSpec {

  "ExpSignedAmount" should {
    "accept a negative amount" in {
      ExpSignedAmount.parse("-12.34").map(_.value) mustBe Right(BigDecimal("-12.34"))
    }
    "accept zero" in {
      ExpSignedAmount.parse("0").map(_.value) mustBe Right(BigDecimal("0"))
    }
    "accept a positive amount" in {
      ExpSignedAmount.parse("12.34").map(_.value) mustBe Right(BigDecimal("12.34"))
    }
    "reject blank input" in {
      ExpSignedAmount.validate("  ").hasError mustBe true
    }
    "reject garbage" in {
      ExpSignedAmount.validate("12,34abc").hasError mustBe true
    }
    "reject exponent notation" in {
      ExpSignedAmount.validate("1e5").hasError mustBe true
      ExpSignedAmount.validate("1E+30").hasError mustBe true
    }
    "accept 10 integer digits and reject 11" in {
      ExpSignedAmount.validate("-1234567890.12").isValid mustBe true
      ExpSignedAmount.validate("-12345678901").hasError mustBe true
    }
    "accept 2 decimals and reject 3" in {
      ExpSignedAmount.validate("1.23").isValid mustBe true
      ExpSignedAmount.validate("1.234").hasError mustBe true
    }
  }

  "ExpSignedAmount (optional)" should {
    "treat blank as no value" in {
      ExpSignedAmount.optionalValidator(" ").toOption mustBe Some(None)
    }
    "accept a negative amount" in {
      ExpSignedAmount.optionalValidator("-1").toOption.flatten.map(_.value) mustBe Some(BigDecimal("-1"))
    }
    "reject garbage" in {
      ExpSignedAmount.optionalValidator("abc").hasError mustBe true
    }
    "reject an out-of-bounds amount" in {
      ExpSignedAmount.optionalValidator("1.234").hasError mustBe true
    }
  }

  "ExpDraftDescription" should {
    val max = BankStatementDraftLimits.MaxDescriptionLength

    "accept a description at the limit" in {
      ExpDraftDescription.validate("x" * max).isValid mustBe true
    }
    "reject a description over the limit" in {
      ExpDraftDescription.validate("x" * (max + 1)).hasError mustBe true
    }
    "reject a blank description" in {
      ExpDraftDescription.validate("   ").hasError mustBe true
    }
  }

  "ExpDraftTxnType (optional)" should {
    val max = BankStatementDraftLimits.MaxTxnTypeLength

    "treat blank as no type" in {
      ExpDraftTxnType.optionalValidator("").toOption mustBe Some(None)
    }
    "accept a type at the limit" in {
      ExpDraftTxnType.optionalValidator("x" * max).toOption.flatten.map(_.string) mustBe Some("x" * max)
    }
    "reject a type over the limit" in {
      ExpDraftTxnType.optionalValidator("x" * (max + 1)).hasError mustBe true
    }
  }
}
