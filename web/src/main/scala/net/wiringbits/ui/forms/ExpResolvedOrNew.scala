package net.wiringbits.ui.forms

import java.util.UUID

/** The value of a "pick an existing entity or create a new one" field.
  *
  * A select/create autocomplete cannot be modelled as a single validated primitive the way a plain text field can: the
  * committed value is either a reference to an existing row (`Existing`), a validated new value to be created
  * (`Create`), or nothing (`Empty`). Holding this in a `FormField[ExpResolvedOrNew[T]]` lets the new-value path be
  * validated live through the primitive's `TextValidator`, so the submit flow reads an already-valid `Create(value)`
  * and never parses user input at send time.
  */
enum ExpResolvedOrNew[+T] {
  case Existing(id: UUID)
  case Create(value: T)
  case Empty
}
