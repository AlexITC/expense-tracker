package net.wiringbits.common.models

import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class CategoryNameSpec extends AnyWordSpec {

  "validate" should {
    "accept a name of exactly 25 characters" in {
      val input = "a" * 25
      CategoryName.validate(input) match {
        case ValidationResult.Valid(_, value) => value.string must be(input)
        case other => fail(s"Unexpected result: $other")
      }
    }

    "keep surrounding whitespace untouched" in {
      CategoryName.validate(" Food ").toOption.map(_.string) must be(Some(" Food "))
    }

    "reject a name of 26 characters" in {
      CategoryName.validate("a" * 26) must be(
        ValidationResult.Invalid("a" * 26, "Name must be 25 characters or less")
      )
    }

    "reject blank names" in {
      List("", "   ").foreach { input =>
        CategoryName.validate(input) must be(ValidationResult.Invalid(input, "The name is required"))
      }
    }
  }

  "parse" should {
    "return the name when valid" in {
      CategoryName.parse("Food").map(_.string) must be(Right("Food"))
    }

    "return the validation error when invalid" in {
      CategoryName.parse("  ") must be(Left("The name is required"))
    }
  }
}
