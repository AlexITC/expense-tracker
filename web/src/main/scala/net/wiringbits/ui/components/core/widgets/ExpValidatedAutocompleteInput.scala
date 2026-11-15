package net.wiringbits.ui.components.core.widgets

import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.AutocompleteRenderInputParams
import com.olvind.mui.muiMaterial.components as mui
import com.olvind.mui.muiMaterial.textFieldTextFieldMod.OutlinedTextFieldProps
import net.wiringbits.ui.forms.ExpResolvedOrNew
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField
import slinky.core.FunctionalComponent
import slinky.core.facade.ReactElement

import java.util.UUID
import scala.scalajs.js

/** The autocomplete analogue of [[ValidatedTextInput]].
  *
  * The field holds an [[ExpResolvedOrNew]]: selecting an option yields `Existing(id)`, typing a new value is validated
  * live through the primitive's `TextValidator` into `Create(value)` (or an inline error), and a blank input is
  * `Empty`. The freeSolo combobox UX is preserved, and nothing is parsed at submit: the form reads an already-valid
  * `Create(value)` straight from the field.
  */
abstract class ExpValidatedAutocompleteInput[Opt, T: TextValidator] {
  private val validator = implicitly[TextValidator[T]]

  case class Props(
      field: FormField[ExpResolvedOrNew[T]],
      options: Seq[Opt],
      label: String,
      getOptionLabel: js.Any => String,
      selectExisting: js.Any => Option[(UUID, String)],
      onChange: ValidationResult[ExpResolvedOrNew[T]] => Unit,
      disabled: Boolean = false,
      fullWidth: Boolean = true
  )

  private def validateText(text: String): ValidationResult[ExpResolvedOrNew[T]] =
    if (text.trim.isEmpty) ValidationResult.Valid(text, ExpResolvedOrNew.Empty)
    else validator(text).map(ExpResolvedOrNew.Create(_))

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
      getOptionLabel = props.getOptionLabel,
      onSelect = value =>
        props.selectExisting(value) match {
          case Some((id, label)) => props.onChange(ValidationResult.Valid(label, ExpResolvedOrNew.Existing(id)))
          case None => ()
        },
      // A "reset" input event fires right after a selection with the picked label; re-validating it would turn the
      // Existing(id) back into a Create(name), so only a real user edit re-validates.
      onInput = (text, reason) => if (reason != "reset") props.onChange(validateText(text)),
      disabled = props.disabled,
      fullWidth = props.fullWidth
    )

    (mui.Autocomplete.withProps(acProps).withKey("ac"): ReactElement)
  }
}
