package net.wiringbits.ui.components.core.widgets

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.AutocompleteRenderInputParams
import com.olvind.mui.muiMaterial.components as mui
import com.olvind.mui.muiMaterial.textFieldTextFieldMod.OutlinedTextFieldProps
import net.wiringbits.ui.forms.ExistingOrNewValue
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField
import slinky.core.FunctionalComponent
import slinky.core.facade.ReactElement

import scala.reflect.ClassTag
import scala.scalajs.js

/** The select/create analogue of [[ValidatedTextInput]].
  *
  * The field holds `Option[ExistingOrNewValue[T, Id]]`: blank is `None`, typing validates the text live through the
  * primitive's `TextValidator` into `Some(ExistingOrNewValue(value, id = None))`, and picking an option sets the same
  * shape with the resolved `id`. Editing after a pick clears the `id`, so the name is always validated like any text
  * field and nothing is parsed at submit.
  *
  * An input is created with functions for the option's label and id. Narrowing the raw value MUI hands back (a selected
  * option, or a freeSolo typed `String`) is done here with the `Opt` `ClassTag`, since the erased type parameter cannot
  * be matched directly. All the `js.*` interop stays inside this component; its `Props` are plain Scala.
  */
class ExpValidatedAutocompleteInput[Opt: ClassTag, T: TextValidator, Id](
    optionLabel: Opt => String,
    optionId: Opt => Id
) {
  private val validator = implicitly[TextValidator[T]]
  private val optionTag = implicitly[ClassTag[Opt]]

  type Value = Option[ExistingOrNewValue[T, Id]]

  private def asOption(value: Any): Option[Opt] =
    value match {
      case optionTag(option) => Some(option)
      case _ => None
    }

  case class Props(
      field: FormField[Value],
      options: Seq[Opt],
      label: String,
      onChange: ValidationResult[Value] => Unit,
      disabled: Boolean = false,
      fullWidth: Boolean = true
  )

  private def validateText(text: String): ValidationResult[Value] =
    if (text.trim.isEmpty) ValidationResult.Valid(text, None)
    else validator(text).map(value => Some(ExistingOrNewValue(value = value, id = None)))

  private def selectedOption(option: Opt): ValidationResult[Value] =
    validator(optionLabel(option)).map(value => Some(ExistingOrNewValue(value = value, id = Some(optionId(option)))))

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val helperText = props.field.value.flatMap(_.errorMessage).getOrElse("")
    val hasError = props.field.value.exists(_.hasError)
    val inputValue = props.field.value.map(_.input).getOrElse("")

    def renderInput(params: AutocompleteRenderInputParams): ReactElement = {
      val tfProps = MuiCompat.textFieldProps(params, props.label, size = Some("small")).asInstanceOf[js.Dynamic]
      tfProps.error = hasError
      tfProps.helperText = helperText
      tfProps.required = props.field.required
      (mui.TextField.outlined
        .withProps(tfProps.asInstanceOf[OutlinedTextFieldProps])
        .withKey("tf"): ReactElement)
    }

    val acProps = MuiCompat.freeSoloAutocompleteProps[Opt](
      options = props.options,
      renderInput = renderInput,
      inputValue = inputValue,
      getOptionLabel = raw =>
        (raw: Any) match {
          case text: String => text
          case other => asOption(other).map(optionLabel).getOrElse("")
        },
      onSelect = raw => asOption(raw).foreach(option => props.onChange(selectedOption(option))),
      // A "reset" input event fires right after a selection with the picked label; re-validating it would drop the
      // resolved id, so only a real user edit re-validates (and clears the id).
      onInput = (text, reason) => if (reason != "reset") props.onChange(validateText(text)),
      disabled = props.disabled,
      fullWidth = props.fullWidth
    )

    (mui.Autocomplete.withProps(acProps).withKey(props.field.name): ReactElement)
  }
}
