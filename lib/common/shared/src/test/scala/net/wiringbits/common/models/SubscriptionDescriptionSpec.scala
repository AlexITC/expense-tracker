package net.wiringbits.common.models

import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionDescriptionSpec extends AnyWordSpec {

  private val tooLongError = "Description must be 500 characters or less"

  "validate" should {
    "accept a description of exactly 500 characters" in {
      SubscriptionDescription.validate("a" * 500).toOption.map(_.string) must be(Some("a" * 500))
    }

    "reject a description of 501 characters" in {
      SubscriptionDescription.validate("a" * 501) must be(ValidationResult.Invalid("a" * 501, tooLongError))
    }

    "trim surrounding whitespace before checking the length" in {
      val input = "  " + ("a" * 500) + "  "
      SubscriptionDescription.validate(input).toOption.map(_.string) must be(Some("a" * 500))
    }

    "accept blank input as an empty value" in {
      SubscriptionDescription.validate("   ").toOption.map(_.string) must be(Some(""))
    }
  }

  "parseOptional" should {
    "return None for a missing value" in {
      SubscriptionDescription.parseOptional(None) must be(Right(None))
    }

    "return None for a blank value" in {
      SubscriptionDescription.parseOptional(Some("   ")) must be(Right(None))
    }

    "return the trimmed value for a valid description" in {
      SubscriptionDescription.parseOptional(Some("  Family plan shared with roommates ")).map(_.map(_.string)) must be(
        Right(Some("Family plan shared with roommates"))
      )
    }

    "return the error for an over-limit description" in {
      SubscriptionDescription.parseOptional(Some("a" * 501)) must be(Left(tooLongError))
    }
  }
}
