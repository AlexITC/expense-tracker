package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class ExpLocalDateSpec extends AnyWordSpec {

  private val formatError = "Date must be in YYYY-MM-DD format"

  "ExpLocalDate.validate" should {
    "accept a valid ISO date and round-trip it through toString" in {
      ExpLocalDate.validate("2024-03-15").toOption.map(_.toString) must be(Some("2024-03-15"))
    }
    "trim surrounding whitespace" in {
      ExpLocalDate.validate(" 2024-03-15 ").toOption.map(_.toString) must be(Some("2024-03-15"))
    }
    "reject malformed dates with the format message" in {
      List("2024-13-01", "01/02/2024", "abc").foreach { input =>
        val result = ExpLocalDate.validate(input)
        result.hasError must be(true)
        result.errorMessage must be(Some(formatError))
      }
    }
    "reject a blank date" in {
      ExpLocalDate.validate("  ").errorMessage must be(Some(formatError))
    }
  }

  "ExpLocalDate.parse" should {
    "return Right for a valid date" in {
      ExpLocalDate.parse("2024-03-15").map(_.toString) must be(Right("2024-03-15"))
    }
    "return Left with the message for an invalid date" in {
      ExpLocalDate.parse("abc") must be(Left(formatError))
    }
  }

  "ExpLocalDate.optionalValidator" should {
    "treat a blank date as no date" in {
      ExpLocalDate.optionalValidator("  ").toOption must be(Some(None))
    }
    "wrap a valid date" in {
      ExpLocalDate.optionalValidator("2024-03-15").toOption.map(_.map(_.toString)) must be(Some(Some("2024-03-15")))
    }
    "reject a malformed date" in {
      ExpLocalDate.optionalValidator("2024-13-01").hasError must be(true)
    }
  }
}
