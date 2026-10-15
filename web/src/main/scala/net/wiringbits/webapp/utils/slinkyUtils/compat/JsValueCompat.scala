package net.wiringbits.webapp.utils.slinkyUtils.compat

import scala.scalajs.js

object JsValueCompat {
  def asString(v: js.Any): String =
    if (js.typeOf(v) == "string") v.asInstanceOf[String] else ""

  def asDouble(v: js.Any): Double =
    if (js.typeOf(v) == "number") {
      val d = v.asInstanceOf[Double]
      if (!d.isNaN) d else 0.0
    } else 0.0
}
