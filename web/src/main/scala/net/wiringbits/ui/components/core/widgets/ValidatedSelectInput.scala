package net.wiringbits.ui.components.core.widgets

import com.olvind.mui.muiMaterial.components as mui

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField
import org.scalajs.dom
import slinky.core.FunctionalComponent

/** Select analogue of [[ValidatedTextInput]]: renders a MUI select over `options` (value -> label) and runs the
  * validator on the picked value. An optional blank entry (`noneLabel`) comes first.
  */
abstract class ValidatedSelectInput[T: TextValidator] {
  private val validator = implicitly[TextValidator[T]]

  case class Props(
      field: FormField[T],
      options: Seq[(String, String)],
      onChange: ValidationResult[T] => Unit,
      disabled: Boolean = false,
      noneLabel: Option[String]
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    def onChange(selected: String): Unit = props.onChange(validator(selected))

    val helperText = props.field.value.flatMap(_.errorMessage).getOrElse("")
    val value = props.field.value.map(_.input).getOrElse("")
    val hasError = props.field.value.exists(_.hasError)
    val noneItem = props.noneLabel.map { label =>
      mui.MenuItem.normal()(label).value("").withKey(s"${props.field.name}-none").build
    }
    val optionItems = props.options.map { case (optionValue, label) =>
      mui.MenuItem.normal()(label).value(optionValue).withKey(optionValue).build
    }

    mui.TextField
      .outlined()
      .select(true)
      .id(s"ValidatedSelectInput-${props.field.name}")
      .name(s"ValidatedSelectInput-${props.field.name}")
      .label(props.field.label)
      .required(props.field.required)
      .fullWidth(true)
      .disabled(props.disabled)
      .margin("dense")
      .error(hasError)
      .helperText(helperText)
      .value(value)
      .onChange(e => onChange(e.target.asInstanceOf[dom.HTMLInputElement].value))(
        (noneItem.toList ++ optionItems)*
      )
  }
}
