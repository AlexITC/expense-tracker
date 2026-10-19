package net.wiringbits.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

class JevConfigSpec extends AnyWordSpec with Matchers {

  private def config(mode: String): Configuration =
    Configuration(
      ConfigFactory.parseString(
        s"""
           |mode = "$mode"
           |apiKey = "super-secret-jev-key"
           |baseUrl = "https://jev.example.com"
           |model = "jev-test"
           |""".stripMargin
      )
    )

  "apply" should {
    "parse all fields" in {
      val result = JevConfig(config("real"))
      result.apiKey.string must be("super-secret-jev-key")
      result.baseUrl must be("https://jev.example.com")
      result.model must be("jev-test")
    }

    "parse the mock mode" in {
      JevConfig(config("mock")).mode must be(JevConfig.Mock)
    }

    "parse the real mode" in {
      JevConfig(config("real")).mode must be(JevConfig.Real)
    }

    "fail on an unknown mode" in {
      val error = intercept[IllegalArgumentException](JevConfig(config("fake")))
      error.getMessage must be("Unknown jev.mode 'fake'; expected 'mock' or 'real'")
    }
  }

  "toString" should {
    "not reveal the api key" in {
      val result = JevConfig(config("real")).toString
      result must not include "super-secret-jev-key"
      result must include("https://jev.example.com")
      result must include("jev-test")
      result must include("Real")
    }
  }
}
