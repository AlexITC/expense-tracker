package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class ExpTaxIdSpec extends AnyWordSpec {

  private val lengthError = s"Tax ID must be ${ExpTaxId.MaxLength} characters or less"

  "validate" should {
    "accept a valid value and round-trip it through string" in {
      ExpTaxId.validate("TAX-123").toOption.map(_.string) must be(Some("TAX-123"))
    }
    "store the trimmed value and keep the original input" in {
      val result = ExpTaxId.validate("  TAX-123  ")
      result.toOption.map(_.string) must be(Some("TAX-123"))
      result.input must be("  TAX-123  ")
    }
    "accept exactly MaxLength characters" in {
      ExpTaxId.validate("a" * ExpTaxId.MaxLength).hasError must be(false)
    }
    "reject MaxLength + 1 characters with the length message" in {
      val result = ExpTaxId.validate("a" * (ExpTaxId.MaxLength + 1))
      result.hasError must be(true)
      result.errorMessage must be(Some(lengthError))
    }
  }

  "parse" should {
    "return Right for a valid value" in {
      ExpTaxId.parse("TAX-123").map(_.string) must be(Right("TAX-123"))
    }
    "return Left with the message for an over-limit value" in {
      ExpTaxId.parse("a" * (ExpTaxId.MaxLength + 1)) must be(Left(lengthError))
    }
  }

  "optionalValidator" should {
    "treat blank or whitespace input as no tax id" in {
      ExpTaxId.optionalValidator("").toOption must be(Some(None))
      ExpTaxId.optionalValidator("   ").toOption must be(Some(None))
    }
    "wrap a valid value" in {
      ExpTaxId.optionalValidator("TAX-123").toOption.map(_.map(_.string)) must be(Some(Some("TAX-123")))
    }
    "reject an over-limit value" in {
      val result = ExpTaxId.optionalValidator("a" * (ExpTaxId.MaxLength + 1))
      result.hasError must be(true)
      result.errorMessage must be(Some(lengthError))
    }
  }
}
