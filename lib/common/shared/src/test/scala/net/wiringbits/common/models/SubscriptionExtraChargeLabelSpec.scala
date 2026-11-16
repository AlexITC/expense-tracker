package net.wiringbits.common.models

import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionExtraChargeLabelSpec extends AnyWordSpec {

  private val tooLongError = "Label must be 100 characters or less"

  "validate" should {
    "accept a label of exactly 100 characters" in {
      SubscriptionExtraChargeLabel.validate("a" * 100).toOption.map(_.string) must be(Some("a" * 100))
    }

    "trim surrounding whitespace" in {
      SubscriptionExtraChargeLabel.validate("  Yearly fee ").toOption.map(_.string) must be(Some("Yearly fee"))
    }

    "measure the length after trimming" in {
      val input = s"  ${"a" * 100}  "
      SubscriptionExtraChargeLabel.validate(input).toOption.map(_.string) must be(Some("a" * 100))
    }

    "reject a label of 101 characters" in {
      SubscriptionExtraChargeLabel.validate("a" * 101) must be(ValidationResult.Invalid("a" * 101, tooLongError))
    }

    "reject blank labels" in {
      List("", "   ").foreach { input =>
        SubscriptionExtraChargeLabel.validate(input) must be(ValidationResult.Invalid(input, "The label is required"))
      }
    }
  }
}
