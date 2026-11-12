package net.wiringbits.webapp.utils.slinkyUtils.compat

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import slinky.core.facade.Fragment

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

  "freeSoloAutocompleteProps" should {
    def build(
        onSelect: js.Any => Unit = _ => (),
        onInput: (String, String) => Unit = (_, _) => (),
        disabled: Boolean = false,
        fullWidth: Boolean = false
    ): js.Dynamic =
      MuiCompat
        .freeSoloAutocompleteProps[String](
          options = Seq("a", "b"),
          renderInput = _ => Fragment(),
          inputValue = "typed",
          getOptionLabel = opt => s"label-$opt",
          onSelect = onSelect,
          onInput = onInput,
          disabled = disabled,
          fullWidth = fullWidth
        )
        .asInstanceOf[js.Dynamic]

    "set freeSolo, small size and pass inputValue through" in {
      val dyn = build()
      dyn.freeSolo.asInstanceOf[Boolean] must be(true)
      dyn.size.asInstanceOf[String] must be("small")
      dyn.inputValue.asInstanceOf[String] must be("typed")
    }

    "pass the options through" in {
      build().options.asInstanceOf[js.Array[String]].length must be(2)
    }

    "leave disabled and fullWidth undefined by default" in {
      val dyn = build()
      js.isUndefined(dyn.disabled) must be(true)
      js.isUndefined(dyn.fullWidth) must be(true)
    }

    "set disabled and fullWidth when true" in {
      val dyn = build(disabled = true, fullWidth = true)
      dyn.disabled.asInstanceOf[Boolean] must be(true)
      dyn.fullWidth.asInstanceOf[Boolean] must be(true)
    }

    "delegate getOptionLabel" in {
      val label = build().getOptionLabel.asInstanceOf[js.Function1[js.Any, String]]
      label("a") must be("label-a")
    }

    "forward the raw selected value to onSelect" in {
      var received: js.Any = js.undefined
      val dyn = build(onSelect = v => received = v)
      val handler = dyn.onChange.asInstanceOf[js.Function4[js.Any, js.Any, js.Any, js.Any, Unit]]
      handler(js.undefined, "b", "selectOption", js.undefined)
      received must be("b")
    }

    "forward string input text and reason to onInput" in {
      var received = ("x", "x")
      val dyn = build(onInput = (text, reason) => received = (text, reason))
      val handler = dyn.onInputChange.asInstanceOf[js.Function3[js.Any, js.Any, js.Any, Unit]]
      handler(js.undefined, "abc", "input")
      received must be(("abc", "input"))
    }

    "map non-string input values to empty strings" in {
      var received = ("x", "x")
      val dyn = build(onInput = (text, reason) => received = (text, reason))
      val handler = dyn.onInputChange.asInstanceOf[js.Function3[js.Any, js.Any, js.Any, Unit]]
      handler(js.undefined, 42, js.undefined)
      received must be(("", ""))
    }
  }
}
