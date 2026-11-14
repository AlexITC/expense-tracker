package net.wiringbits.common.models

import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionNameSpec extends AnyWordSpec {

  "validate" should {
    "accept a name of exactly 60 characters" in {
      val input = "a" * 60
      SubscriptionName.validate(input) match {
        case ValidationResult.Valid(_, value) => value.string must be(input)
        case other => fail(s"Unexpected result: $other")
      }
    }

    "keep surrounding whitespace untouched" in {
      SubscriptionName.validate(" Streaming ").toOption.map(_.string) must be(Some(" Streaming "))
    }

    "reject a name of 61 characters" in {
      SubscriptionName.validate("a" * 61) must be(
        ValidationResult.Invalid("a" * 61, "Name must be 60 characters or less")
      )
    }

    "reject blank names" in {
      List("", "   ").foreach { input =>
        SubscriptionName.validate(input) must be(ValidationResult.Invalid(input, "The name is required"))
      }
    }
  }

  "parse" should {
    "return the name when valid" in {
      SubscriptionName.parse("Streaming").map(_.string) must be(Right("Streaming"))
    }

    "return the validation error when invalid" in {
      SubscriptionName.parse("  ") must be(Left("The name is required"))
    }
  }
}
