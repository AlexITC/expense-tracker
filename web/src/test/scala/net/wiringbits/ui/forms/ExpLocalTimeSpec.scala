package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class ExpLocalTimeSpec extends AnyWordSpec {

  private val formatError = "Time must be in HH:MM format"

  "validate" should {
    "accept a valid HH:MM time and round-trip it through toString" in {
      ExpLocalTime.validate("09:15").toOption.map(_.toString) must be(Some("09:15"))
    }
    "accept a time with seconds" in {
      ExpLocalTime.validate("09:15:30").toOption.map(_.toString) must be(Some("09:15:30"))
    }
    "trim surrounding whitespace" in {
      ExpLocalTime.validate(" 09:15 ").toOption.map(_.toString) must be(Some("09:15"))
    }
    "reject malformed times with the format message" in {
      List("25:00", "9.15", "abc").foreach { input =>
        val result = ExpLocalTime.validate(input)
        result.hasError must be(true)
        result.errorMessage must be(Some(formatError))
      }
    }
    "reject a blank time" in {
      ExpLocalTime.validate("  ").errorMessage must be(Some(formatError))
    }
  }

  "parse" should {
    "return Right for a valid time" in {
      ExpLocalTime.parse("09:15").map(_.toString) must be(Right("09:15"))
    }
    "return Left with the message for an invalid time" in {
      ExpLocalTime.parse("abc") must be(Left(formatError))
    }
  }

  "optionalValidator" should {
    "treat a blank time as no time" in {
      ExpLocalTime.optionalValidator("  ").toOption must be(Some(None))
    }
    "wrap a valid time" in {
      ExpLocalTime.optionalValidator("09:15").toOption.map(_.map(_.toString)) must be(Some(Some("09:15")))
    }
    "reject a malformed time" in {
      ExpLocalTime.optionalValidator("25:00").errorMessage must be(Some(formatError))
    }
  }
}
