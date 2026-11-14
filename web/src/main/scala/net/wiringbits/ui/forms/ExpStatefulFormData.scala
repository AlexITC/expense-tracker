package net.wiringbits.ui.forms

import net.wiringbits.webapp.utils.slinkyUtils.forms.FormData

/** Experimental mirror of the library's `StatefulFormData`, refactored to sit on top of the standalone [[SubmitState]].
  *
  * The public API is identical to the library's version, so a form that used the library type only needs to swap the
  * import. The point of the PoC: the lifecycle (`SubmitState`) is now a separate, shape-independent value that other
  * forms can use without a `FormData` at all (see [[SubmitHooks]]), while text-field forms keep this convenience
  * wrapper that pairs their validated `data` with that lifecycle.
  */
case class ExpStatefulFormData[D <: FormData[?]](data: D, state: SubmitState = SubmitState.Filling) {

  def firstValidationError: Option[String] = state.submissionError.orElse(data.formValidationErrors.headOption)

  def submissionError: Option[String] = state.submissionError

  val isValid: Boolean = data.isValid && state.submissionError.isEmpty

  val isSubmitting: Boolean = state.isSubmitting

  val isInputDisabled: Boolean = isSubmitting

  val isSubmitButtonEnabled: Boolean = isValid && !isSubmitting

  val isSubmitButtonDisabled: Boolean = !isSubmitButtonEnabled

  def submit: ExpStatefulFormData[D] = copy(state = SubmitState.Submitting)

  def submissionFailed(error: String): ExpStatefulFormData[D] = copy(state = SubmitState.Failed(error))

  def submitted: ExpStatefulFormData[D] = copy(state = SubmitState.Submitted)

  def filling: ExpStatefulFormData[D] = copy(state = SubmitState.Filling)
}
