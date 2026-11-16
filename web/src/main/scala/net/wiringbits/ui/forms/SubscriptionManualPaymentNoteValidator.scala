package net.wiringbits.ui.forms

import net.wiringbits.common.models.SubscriptionManualPaymentNote
import net.wiringbits.webapp.common.validators.TextValidator

/** The optional manual payment note input: blank means no note. */
object SubscriptionManualPaymentNoteValidator {
  given optionalValidator: TextValidator[Option[SubscriptionManualPaymentNote]] =
    OptionalValidator.optional(SubscriptionManualPaymentNote.validate)
}
