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

/** How a select/create field's value `V` is built from a selection, and what a blank field means. A required field (`V
  * \= ExistingOrNewValue[T, Id]`) treats blank as invalid; an optional one (`V = Option[ExistingOrNewValue[T, Id]]`)
  * treats blank as a valid `None`. This is the select/create analogue of `TextValidator.optionValidator`.
  */
trait SelectionShape[T, Id, V] {
  def blank(input: String): ValidationResult[V]
  def present(input: String, value: ExistingOrNewValue[T, Id]): ValidationResult[V]
}

object SelectionShape {
  given required[T, Id]: SelectionShape[T, Id, ExistingOrNewValue[T, Id]] with {
    def blank(input: String): ValidationResult[ExistingOrNewValue[T, Id]] =
      ValidationResult.Invalid(input = input, error = "Select an existing entry or type a new one")
    def present(input: String, value: ExistingOrNewValue[T, Id]): ValidationResult[ExistingOrNewValue[T, Id]] =
      ValidationResult.Valid(input, value)
  }

  given optional[T, Id]: SelectionShape[T, Id, Option[ExistingOrNewValue[T, Id]]] with {
    def blank(input: String): ValidationResult[Option[ExistingOrNewValue[T, Id]]] =
      ValidationResult.Valid(input, None)
    def present(
        input: String,
        value: ExistingOrNewValue[T, Id]
    ): ValidationResult[Option[ExistingOrNewValue[T, Id]]] =
      ValidationResult.Valid(input, Some(value))
  }
}

/** The select/create analogue of [[ValidatedTextInput]].
  *
  * Typing validates the text live through the primitive's `TextValidator`, picking an option sets the same shape with
  * the resolved `id`, and editing after a pick clears the `id`. The field's value type `V` is chosen by the
  * [[SelectionShape]] in scope: `ExistingOrNewValue[T, Id]` for a required field, `Option[...]` for an optional one.
  *
  * An input is created with functions for the option's label and id. Narrowing the raw value MUI hands back (a selected
  * option, or a freeSolo typed `String`) is done here with the `Opt` `ClassTag`, since the erased type parameter cannot
  * be matched directly. All the `js.*` interop stays inside this component; its `Props` are plain Scala.
  */
class ExpValidatedAutocompleteInput[Opt: ClassTag, T: TextValidator, Id, V](
    optionLabel: Opt => String,
    optionId: Opt => Id
)(using shape: SelectionShape[T, Id, V]) {
  private val validator = implicitly[TextValidator[T]]
  private val optionTag = implicitly[ClassTag[Opt]]

  type Value = V

  private def asOption(value: Any): Option[Opt] =
    value match {
      case optionTag(option) => Some(option)
      case _ => None
    }

  private def validateText(text: String): ValidationResult[Value] =
    if (text.trim.isEmpty) shape.blank(text)
    else
      validator(text) match {
        case ValidationResult.Valid(input, value) => shape.present(input, ExistingOrNewValue(value = value, id = None))
        case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
      }

  private def selectedOption(option: Opt): ValidationResult[Value] =
    validator(optionLabel(option)) match {
      case ValidationResult.Valid(input, value) =>
        shape.present(input, ExistingOrNewValue(value = value, id = Some(optionId(option))))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }

  case class Props(
      field: FormField[Value],
      options: Seq[Opt],
      label: String,
      onChange: ValidationResult[Value] => Unit,
      disabled: Boolean = false,
      fullWidth: Boolean = true
  )

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
