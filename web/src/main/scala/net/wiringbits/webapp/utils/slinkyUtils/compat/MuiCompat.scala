package net.wiringbits.webapp.utils.slinkyUtils.compat

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import com.olvind.mui.muiMaterial.paginationPaginationMod.PaginationProps
import com.olvind.mui.muiMaterial.textFieldTextFieldMod.OutlinedTextFieldProps
import slinky.core.facade.ReactElement

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

object MuiCompat {
  // size: MUI Pagination size prop — valid values are "small", "medium", "large"
  def paginationProps(
      count: Int,
      page: Int,
      onChange: Int => Unit,
      size: Option[String] = None
  ): PaginationProps = {
    val handler: js.Function2[js.Any, Double, Unit] = (_, p) => onChange(p.toInt - 1)
    val obj = js.Dynamic.literal(count = count, page = page + 1, onChange = handler)
    size.foreach(s => obj.size = s)
    obj.asInstanceOf[PaginationProps]
  }

  def textFieldProps(
      params: AutocompleteRenderInputParams,
      label: String,
      size: Option[String] = None
  ): OutlinedTextFieldProps = {
    val dyn = js.Object.assign(new js.Object(), params).asInstanceOf[js.Dynamic]
    dyn.label = label
    dyn.variant = "outlined"
    size.foreach(s => dyn.size = s)
    dyn.asInstanceOf[OutlinedTextFieldProps]
  }

  def autocompleteInputChange(value: js.Any, reason: js.Any): (String, String) = (
    if (js.typeOf(value) == "string") value.asInstanceOf[String] else "",
    if (js.typeOf(reason) == "string") reason.asInstanceOf[String] else ""
  )

  // ScalablyTyped's AutocompleteProps does not expose freeSolo/size/inputValue/getOptionLabel/onChange/
  // onInputChange/disabled/fullWidth as settable fields, so they are assigned through js.Dynamic here.
  // Pages must use this helper instead of repeating the cast-and-mutate pattern.
  def freeSoloAutocompleteProps[T](
      options: Seq[T],
      renderInput: AutocompleteRenderInputParams => ReactElement,
      inputValue: String,
      getOptionLabel: js.Any => String,
      onSelect: js.Any => Unit,
      onInput: (String, String) => Unit,
      disabled: Boolean,
      fullWidth: Boolean
  ): AutocompleteProps[T, Boolean, Boolean, Boolean, "div"] = {
    val props = AutocompleteProps[T, Boolean, Boolean, Boolean, "div"](
      options = options.toJSArray,
      renderInput = renderInput
    )
    val dyn = props.asInstanceOf[js.Dynamic]
    dyn.freeSolo = true
    dyn.size = "small"
    dyn.inputValue = inputValue
    dyn.getOptionLabel = (opt: js.Any) => getOptionLabel(opt)
    if (disabled) dyn.disabled = true
    if (fullWidth) dyn.fullWidth = true
    dyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) => onSelect(value)
    dyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
      val (text, rsn) = autocompleteInputChange(value = value, reason = reason)
      onInput(text, rsn)
    }
    props
  }

  def tabValue(v: Any): String =
    if (js.typeOf(v.asInstanceOf[js.Any]) == "string") v.asInstanceOf[String] else ""
}
