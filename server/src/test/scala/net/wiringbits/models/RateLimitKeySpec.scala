package net.wiringbits.models

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class RateLimitKeySpec extends AnyWordSpec {

  "entryName" should {
    "be the case name, which is the stored value" in {
      RateLimitKey.Bedrock.entryName must be("Bedrock")
    }
  }

  "withNameOption" should {
    "round-trip every key through its stored value" in {
      RateLimitKey.values.foreach { key =>
        RateLimitKey.withNameOption(key.entryName) must be(Some(key))
      }
    }

    "return None for an unknown value" in {
      RateLimitKey.withNameOption("Unknown") must be(None)
    }
  }
}
