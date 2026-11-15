package net.wiringbits.ui.forms

import net.wiringbits.common.models.SubscriptionDescription
import net.wiringbits.webapp.common.validators.TextValidator

/** The optional subscription description input: blank means no description. */
object SubscriptionDescriptionValidator {
  given optionalValidator: TextValidator[Option[SubscriptionDescription]] =
    OptionalValidator.optional(SubscriptionDescription.validate)
}
