package net.wiringbits.common.models

import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class MerchantNameSpec extends AnyWordSpec {

  "validate" should {
    "accept a name of exactly 100 characters" in {
      val input = "a" * 100
      MerchantName.validate(input) match {
        case ValidationResult.Valid(_, value) => value.string must be(input)
        case other => fail(s"Unexpected result: $other")
      }
    }

    "keep surrounding whitespace untouched" in {
      MerchantName.validate(" Amazon ").toOption.map(_.string) must be(Some(" Amazon "))
    }

    "reject a name of 101 characters" in {
      MerchantName.validate("a" * 101) must be(
        ValidationResult.Invalid("a" * 101, "Name must be 100 characters or less")
      )
    }

    "reject blank names" in {
      List("", "   ").foreach { input =>
        MerchantName.validate(input) must be(ValidationResult.Invalid(input, "The name is required"))
      }
    }
  }

  "parse" should {
    "return the name when valid" in {
      MerchantName.parse("Amazon").map(_.string) must be(Right("Amazon"))
    }

    "return the validation error when invalid" in {
      MerchantName.parse("  ") must be(Left("The name is required"))
    }
  }
}
