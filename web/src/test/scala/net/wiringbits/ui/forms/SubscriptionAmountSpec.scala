package net.wiringbits.ui.forms

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionAmountSpec extends AnyWordSpec {

  "optionalValidator" should {
    "treat a blank amount as no amount" in {
      SubscriptionAmount.optionalValidator("  ").toOption must be(Some(None))
    }
    "accept a valid amount and normalize its scale" in {
      SubscriptionAmount.optionalValidator("12.5").toOption.flatten.map(_.value.toString) must be(Some("12.50"))
    }
    "reject a non-positive amount" in {
      SubscriptionAmount.optionalValidator("0").hasError must be(true)
    }
    "reject an unparseable amount" in {
      SubscriptionAmount.optionalValidator("abc").hasError must be(true)
    }
    "reject more decimals than the column allows" in {
      SubscriptionAmount.optionalValidator("1.234").hasError must be(true)
    }
    "reject more integer digits than the column allows" in {
      SubscriptionAmount.optionalValidator("12345678901").hasError must be(true)
    }
    "accept the largest amount the column allows" in {
      SubscriptionAmount.optionalValidator("9999999999.99").isValid must be(true)
    }
  }
}
