package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

import java.util.UUID

/** Validated optional id picked from a select: a blank value means "none", anything else must be a well-formed UUID.
  *
  * `UUID`'s companion is a Java class and not in implicit scope, so callers pass `(using ExpOptionalId.validator)`.
  */
object ExpOptionalId {
  private val UuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  def validate(string: String): ValidationResult[Option[UUID]] = {
    val s = string.trim
    if (s.isEmpty) ValidationResult.Valid(string, None)
    else if (UuidPattern.matches(s)) ValidationResult.Valid(string, Some(UUID.fromString(s)))
    else ValidationResult.Invalid(input = string, error = "Select a valid entry")
  }

  def parse(string: String): Either[String, Option[UUID]] = validate(string) match {
    case ValidationResult.Valid(_, value) => Right(value)
    case ValidationResult.Invalid(_, error) => Left(error)
  }

  given validator: TextValidator[Option[UUID]] = (string: String) => validate(string)
}
