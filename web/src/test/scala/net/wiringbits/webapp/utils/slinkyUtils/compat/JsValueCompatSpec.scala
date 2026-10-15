package net.wiringbits.webapp.utils.slinkyUtils.compat

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import scala.scalajs.js

class JsValueCompatSpec extends AnyWordSpec {

  "asString" should {
    "return the string value for a JS string" in {
      JsValueCompat.asString("hello".asInstanceOf[js.Any]) must be("hello")
    }
    "return empty string for a JS number" in {
      JsValueCompat.asString(42.0.asInstanceOf[js.Any]) must be("")
    }
    "return empty string for null" in {
      JsValueCompat.asString(null.asInstanceOf[js.Any]) must be("")
    }
    "return empty string for undefined" in {
      JsValueCompat.asString(js.undefined.asInstanceOf[js.Any]) must be("")
    }
  }

  "asDouble" should {
    "return the numeric value for a JS number" in {
      JsValueCompat.asDouble(3.14.asInstanceOf[js.Any]) must be(3.14)
    }
    "return 0.0 for a JS string" in {
      JsValueCompat.asDouble("hello".asInstanceOf[js.Any]) must be(0.0)
    }
    "return 0.0 for null" in {
      JsValueCompat.asDouble(null.asInstanceOf[js.Any]) must be(0.0)
    }
    "return 0.0 for undefined" in {
      JsValueCompat.asDouble(js.undefined.asInstanceOf[js.Any]) must be(0.0)
    }
    "return 0.0 for NaN" in {
      JsValueCompat.asDouble(Double.NaN.asInstanceOf[js.Any]) must be(0.0)
    }
    "return Infinity for PositiveInfinity" in {
      JsValueCompat.asDouble(Double.PositiveInfinity.asInstanceOf[js.Any]) must be(Double.PositiveInfinity)
    }
  }
}
