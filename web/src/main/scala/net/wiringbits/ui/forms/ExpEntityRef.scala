package net.wiringbits.ui.forms

import java.util.UUID

/** The value of a "pick an existing entity or create a new one" field.
  *
  * Like a plain validated text field it holds the typed text (through its `ValidationResult.input`) and a validated
  * primitive `value`; on top of that it carries the resolved `id` when the current input came from picking an existing
  * row. Validation rules always run on `value`, and the submit flow reads `id`: present means reuse that row, absent
  * means create from the already-valid `value`. A blank field is modelled as `None` by wrapping this in `Option`, the
  * same way the numeric field uses `Option` for "no amount".
  */
case class ExpEntityRef[T](value: T, id: Option[UUID])
