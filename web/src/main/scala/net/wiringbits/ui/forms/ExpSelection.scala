package net.wiringbits.ui.forms

/** The committed value of a pick-existing-or-create field.
  *
  * `value` is the validated primitive derived from the typed text, so unlike a raw `String` it enforces the type's
  * rules (a `CategoryName` cannot be empty where a `String` can). `id` is the identifier of the existing row when the
  * value came from picking one, and absent when the user typed a new value to create. `Id` is a type parameter because
  * not every entity is keyed by `UUID`. A blank field is represented by wrapping this in `Option`.
  */
case class ExpSelection[T, Id](value: T, id: Option[Id])
