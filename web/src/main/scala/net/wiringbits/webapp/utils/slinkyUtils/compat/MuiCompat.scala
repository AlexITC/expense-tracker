package net.wiringbits.webapp.utils.slinkyUtils.compat

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.AutocompleteRenderInputParams
import com.olvind.mui.muiMaterial.paginationPaginationMod.PaginationProps
import com.olvind.mui.muiMaterial.textFieldTextFieldMod.OutlinedTextFieldProps

import scala.scalajs.js

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

  def tabValue(v: Any): String =
    if (js.typeOf(v.asInstanceOf[js.Any]) == "string") v.asInstanceOf[String] else ""
}
