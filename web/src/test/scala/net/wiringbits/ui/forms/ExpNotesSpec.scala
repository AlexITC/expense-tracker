package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class ExpNotesSpec extends AnyWordSpec {

  private val lengthError = s"Notes must be ${ExpNotes.MaxLength} characters or less"

  "validate" should {
    "accept a valid value and round-trip it through string" in {
      ExpNotes.validate("lunch with team").toOption.map(_.string) must be(Some("lunch with team"))
    }
    "store the trimmed value and keep the original input" in {
      val result = ExpNotes.validate("  lunch  ")
      result.toOption.map(_.string) must be(Some("lunch"))
      result.input must be("  lunch  ")
    }
    "accept exactly MaxLength characters" in {
      ExpNotes.validate("a" * ExpNotes.MaxLength).hasError must be(false)
    }
    "reject MaxLength + 1 characters with the length message" in {
      val result = ExpNotes.validate("a" * (ExpNotes.MaxLength + 1))
      result.hasError must be(true)
      result.errorMessage must be(Some(lengthError))
    }
  }

  "parse" should {
    "return Right for a valid value" in {
      ExpNotes.parse("lunch").map(_.string) must be(Right("lunch"))
    }
    "return Left with the message for an over-limit value" in {
      ExpNotes.parse("a" * (ExpNotes.MaxLength + 1)) must be(Left(lengthError))
    }
  }

  "optionalValidator" should {
    "treat blank or whitespace input as no notes" in {
      ExpNotes.optionalValidator("").toOption must be(Some(None))
      ExpNotes.optionalValidator("   ").toOption must be(Some(None))
    }
    "wrap a valid value" in {
      ExpNotes.optionalValidator("lunch").toOption.map(_.map(_.string)) must be(Some(Some("lunch")))
    }
    "reject an over-limit value" in {
      val result = ExpNotes.optionalValidator("a" * (ExpNotes.MaxLength + 1))
      result.hasError must be(true)
      result.errorMessage must be(Some(lengthError))
    }
  }

  "merchantOptionalValidator" should {
    "treat blank input as no notes" in {
      ExpNotes.merchantOptionalValidator("   ").toOption must be(Some(None))
    }
    "accept exactly MerchantMaxLength characters" in {
      ExpNotes.merchantOptionalValidator("a" * ExpNotes.MerchantMaxLength).hasError must be(false)
    }
    "reject MerchantMaxLength + 1 characters with the merchant length message" in {
      val result = ExpNotes.merchantOptionalValidator("a" * (ExpNotes.MerchantMaxLength + 1))
      result.hasError must be(true)
      result.errorMessage must be(Some(s"Notes must be ${ExpNotes.MerchantMaxLength} characters or less"))
    }
  }
}
