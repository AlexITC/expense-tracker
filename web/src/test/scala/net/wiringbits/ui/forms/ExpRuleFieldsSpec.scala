package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

/** The validation that used to live in BankRowRuleForm.buildRequest now lives in these field primitives. */
class ExpRuleFieldsSpec extends AnyWordSpec {

  "ExpRulePattern" should {
    "reject a pattern shorter than the minimum" in {
      ExpRulePattern.validate("ab").hasError must be(true)
    }
    "accept a long-enough pattern" in {
      ExpRulePattern.validate("store").isValid must be(true)
    }
  }

  "ExpRuleAmount (optional)" should {
    "treat a blank amount as no amount" in {
      ExpRuleAmount.optionalValidator("  ").toOption must be(Some(None))
    }
    "reject a non-positive amount" in {
      ExpRuleAmount.optionalValidator("-1").hasError must be(true)
    }
    "reject an unparseable amount" in {
      ExpRuleAmount.optionalValidator("abc").hasError must be(true)
    }
    "reject more decimals than the column allows" in {
      ExpRuleAmount.optionalValidator("1.234").hasError must be(true)
    }
    "accept a valid amount and normalize its scale" in {
      ExpRuleAmount.optionalValidator("12.5").toOption.flatten.map(_.value.toString) must be(Some("12.50"))
    }
  }

  "ExpRuleDescription (optional)" should {
    "treat a blank description as none" in {
      ExpRuleDescription.optionalValidator("  ").toOption must be(Some(None))
    }
    "reject an over-long description" in {
      ExpRuleDescription.optionalValidator("x" * 256).hasError must be(true)
    }
  }
}
