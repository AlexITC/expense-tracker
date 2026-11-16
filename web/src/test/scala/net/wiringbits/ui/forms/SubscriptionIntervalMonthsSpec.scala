package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionIntervalMonthsSpec extends AnyWordSpec {

  "validate" should {
    "accept a whole number within the bounds" in {
      SubscriptionIntervalMonths.validate(" 3 ").toOption.map(_.value) must be(Some(3))
      SubscriptionIntervalMonths.validate("1").toOption.map(_.value) must be(Some(1))
      SubscriptionIntervalMonths.validate("24").toOption.map(_.value) must be(Some(24))
    }
    "reject zero" in {
      SubscriptionIntervalMonths.validate("0").hasError must be(true)
    }
    "reject more than 24 months" in {
      SubscriptionIntervalMonths.validate("25").hasError must be(true)
    }
    "reject a decimal" in {
      SubscriptionIntervalMonths.validate("1.5").hasError must be(true)
    }
    "reject blank input" in {
      SubscriptionIntervalMonths.validate(" ").hasError must be(true)
    }
  }

  "parse" should {
    "return the error for invalid input" in {
      SubscriptionIntervalMonths.parse("abc") must be(Left("Interval months must be a whole number"))
    }
  }
}
