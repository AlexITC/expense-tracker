package net.wiringbits.common.models

import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionManualPaymentNoteSpec extends AnyWordSpec {

  private val tooLongError = "Note must be 255 characters or less"

  "validate" should {
    "accept a note of exactly 255 characters" in {
      SubscriptionManualPaymentNote.validate("a" * 255).toOption.map(_.string) must be(Some("a" * 255))
    }

    "reject a note of 256 characters" in {
      SubscriptionManualPaymentNote.validate("a" * 256) must be(ValidationResult.Invalid("a" * 256, tooLongError))
    }

    "trim surrounding whitespace before checking the length" in {
      val input = "  " + ("a" * 255) + "  "
      SubscriptionManualPaymentNote.validate(input).toOption.map(_.string) must be(Some("a" * 255))
    }
  }

  "parseOptional" should {
    "return None for a missing value" in {
      SubscriptionManualPaymentNote.parseOptional(None) must be(Right(None))
    }

    "return None for a blank value" in {
      SubscriptionManualPaymentNote.parseOptional(Some("   ")) must be(Right(None))
    }

    "return the trimmed value for a valid note" in {
      SubscriptionManualPaymentNote.parseOptional(Some("  Paid in cash at the desk ")).map(_.map(_.string)) must be(
        Right(Some("Paid in cash at the desk"))
      )
    }

    "return the error for an over-limit note" in {
      SubscriptionManualPaymentNote.parseOptional(Some("a" * 256)) must be(Left(tooLongError))
    }
  }
}
