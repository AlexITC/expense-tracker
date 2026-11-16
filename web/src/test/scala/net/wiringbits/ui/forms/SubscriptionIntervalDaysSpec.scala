package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionIntervalDaysSpec extends AnyWordSpec {

  "validate" should {
    "accept a positive whole number" in {
      SubscriptionIntervalDays.validate(" 14 ").toOption.map(_.value) must be(Some(14))
    }
    "reject zero" in {
      SubscriptionIntervalDays.validate("0").hasError must be(true)
    }
    "reject a negative number" in {
      SubscriptionIntervalDays.validate("-3").hasError must be(true)
    }
    "reject a decimal" in {
      SubscriptionIntervalDays.validate("1.5").hasError must be(true)
    }
    "reject blank input" in {
      SubscriptionIntervalDays.validate(" ").hasError must be(true)
    }
  }

  "parse" should {
    "return the error for invalid input" in {
      SubscriptionIntervalDays.parse("abc") must be(Left("Interval days must be a whole number"))
    }
  }
}
