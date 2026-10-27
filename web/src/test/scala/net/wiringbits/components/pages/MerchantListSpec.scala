package net.wiringbits.components.pages

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class MerchantListSpec extends AnyWordSpec {

  "isAtLimit" should {
    "be false when the count is below the max" in {
      MerchantList.isAtLimit(1, 2) must be(false)
    }

    "be true when the count equals the max" in {
      MerchantList.isAtLimit(2, 2) must be(true)
    }

    "be true when the count exceeds the max" in {
      MerchantList.isAtLimit(3, 2) must be(true)
    }
  }

  "usageLabel" should {
    "render the count and the max" in {
      MerchantList.usageLabel(3, 400) must be("3 / 400 merchants")
    }
  }
}
