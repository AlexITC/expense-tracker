package net.wiringbits.webapp.utils.slinkyUtils.compat

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class RouterCompatSpec extends AnyWordSpec {

  "RouterCompat.Location" should {
    "hold the provided pathname and search" in {
      val loc = RouterCompat.Location(pathname = "/foo", search = "?bar=1")
      loc.pathname must be("/foo")
      loc.search must be("?bar=1")
    }

    "support equality" in {
      val a = RouterCompat.Location("/a", "?x=1")
      val b = RouterCompat.Location("/a", "?x=1")
      a must be(b)
    }

    "distinguish instances with different fields" in {
      val a = RouterCompat.Location("/a", "?x=1")
      val b = RouterCompat.Location("/b", "?x=2")
      a must not be b
    }

    "allow empty search string" in {
      val loc = RouterCompat.Location(pathname = "/home", search = "")
      loc.search must be("")
    }
  }
}
