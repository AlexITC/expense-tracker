package net.wiringbits.webapp.utils.slinkyUtils.compat

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import scala.scalajs.js

class MuiCompatSpec extends AnyWordSpec {

  "paginationProps" should {
    "convert 0-based page to 1-based for MUI" in {
      val props = MuiCompat.paginationProps(count = 5, page = 2, onChange = _ => ())
      props.asInstanceOf[js.Dynamic].page.asInstanceOf[Int] must be(3)
    }

    "pass count through unchanged" in {
      val props = MuiCompat.paginationProps(count = 10, page = 0, onChange = _ => ())
      props.asInstanceOf[js.Dynamic].count.asInstanceOf[Int] must be(10)
    }

    "call onChange with 0-based page when handler receives MUI 1-based page" in {
      var received = -1
      val props = MuiCompat.paginationProps(count = 5, page = 0, onChange = p => received = p)
      val handler = props.asInstanceOf[js.Dynamic].onChange.asInstanceOf[js.Function2[js.Any, Double, Unit]]
      handler(js.undefined, 3.0)
      received must be(2)
    }

    "include size in props when provided" in {
      val props = MuiCompat.paginationProps(count = 5, page = 0, onChange = _ => (), size = Some("small"))
      props.asInstanceOf[js.Dynamic].size.asInstanceOf[String] must be("small")
    }

    "omit size field when not provided" in {
      val props = MuiCompat.paginationProps(count = 5, page = 0, onChange = _ => ())
      js.isUndefined(props.asInstanceOf[js.Dynamic].size) must be(true)
    }
  }
}
