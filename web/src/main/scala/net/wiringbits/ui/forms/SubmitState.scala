package net.wiringbits.ui.forms

/** The submission lifecycle of any form, independent of how its fields are modelled.
  *
  * This is the piece that used to live inside the library's `StatefulFormData.State`, which could only be reached
  * through a text-field `FormData`. Pulled out on its own, it is usable by any form: autocomplete-driven, multi-entity,
  * or dynamic, none of which fit the `FormField[T: TextValidator]` shape.
  */
enum SubmitState {
  case Filling
  case Submitting
  case Submitted
  case Failed(error: String)

  def submissionError: Option[String] = this match {
    case Failed(error) => Some(error)
    case _ => None
  }

  def isSubmitting: Boolean = this match {
    case Submitting => true
    case _ => false
  }
}
