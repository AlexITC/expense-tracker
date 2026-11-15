package net.wiringbits.api.models.subscriptions

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

class SubscriptionEnumsSpec extends AnyWordSpec with Matchers {

  "SubscriptionCadence" should {
    "use the spec entry names" in {
      SubscriptionCadence.values.map(_.entryName).toList mustBe List("Monthly", "Yearly", "EveryNDays", "EveryNMonths")
    }

    "resolve every value from its entry name" in {
      SubscriptionCadence.values.foreach { c => SubscriptionCadence.fromEntryName(c.entryName) mustBe c }
    }

    "throw on an unknown entry name" in {
      intercept[NoSuchElementException](SubscriptionCadence.fromEntryName("Weekly")).getMessage must include("Weekly")
    }

    "round-trip through json" in {
      SubscriptionCadence.values.foreach { c =>
        Json.toJson(c) mustBe JsString(c.entryName)
        Json.toJson(c).as[SubscriptionCadence] mustBe c
      }
    }

    "fail to read an unknown json value" in {
      JsString("Weekly").validate[SubscriptionCadence].isError mustBe true
    }
  }

  "SubscriptionPaymentMethod" should {
    "use the spec entry names" in {
      SubscriptionPaymentMethod.values.map(_.entryName).toList mustBe
        List("DirectDebit", "ManualTransfer", "CardPortal", "Cash", "CardAutoCharge")
    }

    "resolve every value from its entry name" in {
      SubscriptionPaymentMethod.values.foreach { m => SubscriptionPaymentMethod.fromEntryName(m.entryName) mustBe m }
    }

    "throw on an unknown entry name" in {
      intercept[NoSuchElementException](SubscriptionPaymentMethod.fromEntryName("Cheque")).getMessage must include(
        "Cheque"
      )
    }

    "round-trip through json" in {
      SubscriptionPaymentMethod.values.foreach { m =>
        Json.toJson(m) mustBe JsString(m.entryName)
        Json.toJson(m).as[SubscriptionPaymentMethod] mustBe m
      }
    }

    "fail to read an unknown json value" in {
      JsString("Cheque").validate[SubscriptionPaymentMethod].isError mustBe true
    }
  }
}
