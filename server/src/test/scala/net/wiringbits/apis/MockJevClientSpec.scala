package net.wiringbits.apis

import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class MockJevClientSpec extends AnyWordSpec {
  private val client = new MockJevClient()

  "choose" should {
    "pick none when it is offered" in {
      val result = client.choose("state", "merchant", "Pick", Map("b" -> "B", "none" -> "No match", "a" -> "A"))
      result.futureValue must be(JevChoice("none", BigDecimal(0), Map.empty))
    }

    "pick the smallest key otherwise" in {
      val criteria = Map("c" -> "C", "a" -> "A", "b" -> "B")
      client.choose("state", "category", "Pick", criteria).futureValue.choice must be("a")
      client.choose("other state", "category", "Pick", criteria).futureValue.choice must be("a")
    }

    "fail when there are no criteria" in {
      val error = client.choose("state", "category", "Pick", Map.empty).failed.futureValue
      error mustBe a[RuntimeException]
      error.getMessage must include("category")
    }
  }
}
