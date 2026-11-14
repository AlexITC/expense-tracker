package net.wiringbits.ui.forms

import slinky.core.facade.Hooks

/** A React hook that gives any form the submission lifecycle, regardless of how its inputs are modelled.
  *
  * The form keeps its own state and computes its own "can submit" gate however it likes; this only owns the
  * Filling/Submitting/Submitted/Failed transitions and the derived flags a view needs. Because `fail` sets both "not
  * submitting" and the error in one move, the "forgot to reset the in-flight flag" bug (a hung spinner) is not
  * possible.
  */
object SubmitHooks {

  final class Control(val state: SubmitState, setState: SubmitState => Unit) {
    def isSubmitting: Boolean = state.isSubmitting
    def error: Option[String] = state.submissionError

    /** Inputs are disabled while a submission is in flight. */
    def inputDisabled: Boolean = state.isSubmitting

    /** The submit button is clickable only when the caller's gate holds and nothing is in flight. */
    def buttonDisabled(canSubmit: Boolean): Boolean = state.isSubmitting || !canSubmit

    def begin(): Unit = setState(SubmitState.Submitting)
    def succeed(): Unit = setState(SubmitState.Submitted)
    def fail(error: String): Unit = setState(SubmitState.Failed(error))
    def reset(): Unit = setState(SubmitState.Filling)

    /** Drops a stale submission error without disturbing an in-flight submission, for when the form changes and the
      * previous failure no longer applies.
      */
    def clearError(): Unit = state match {
      case SubmitState.Failed(_) => setState(SubmitState.Filling)
      case _ => ()
    }
  }

  def useSubmit(): Control = {
    val (state, setState) = Hooks.useState[SubmitState](SubmitState.Filling)
    new Control(state, setState)
  }
}
