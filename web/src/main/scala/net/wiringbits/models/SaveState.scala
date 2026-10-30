package net.wiringbits.models

/** Progress of a save-style action (save/confirm/discard) on an editable page. */
enum SaveState {
  case Idle
  case Saving
  case Failed(message: String)

  def isSaving: Boolean = this match {
    case Saving => true
    case _ => false
  }

  def errorOpt: Option[String] = this match {
    case Failed(message) => Some(message)
    case _ => None
  }
}
