package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class ExpOptionalIdSpec extends AnyWordSpec {

  private val id = UUID.fromString("7f1c2a3e-4b5d-4e6f-8a9b-0c1d2e3f4a5b")
  private val selectError = "Select a valid entry"

  "validate" should {
    "treat a blank value as no id" in {
      ExpOptionalId.validate("").toOption must be(Some(None))
    }
    "treat a whitespace value as no id" in {
      ExpOptionalId.validate("   ").toOption must be(Some(None))
    }
    "accept a well-formed UUID and keep the input" in {
      val result = ExpOptionalId.validate(id.toString)
      result.toOption must be(Some(Some(id)))
      result.input must be(id.toString)
    }
    "trim a padded UUID and keep the padded input" in {
      val padded = s"  $id  "
      val result = ExpOptionalId.validate(padded)
      result.toOption must be(Some(Some(id)))
      result.input must be(padded)
    }
    "reject a non-canonical id that UUID.fromString would tolerate" in {
      ExpOptionalId.validate("1-1-1-1-1").errorMessage must be(Some(selectError))
    }
    "reject a malformed id with the message" in {
      val result = ExpOptionalId.validate("not-a-uuid")
      result.hasError must be(true)
      result.errorMessage must be(Some(selectError))
    }
  }

  "parse" should {
    "return Right for a valid id" in {
      ExpOptionalId.parse(id.toString) must be(Right(Some(id)))
    }
    "return Right(None) for a blank value" in {
      ExpOptionalId.parse("") must be(Right(None))
    }
    "return Left with the message for a malformed id" in {
      ExpOptionalId.parse("not-a-uuid") must be(Left(selectError))
    }
  }
}
