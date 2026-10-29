package net.wiringbits.repositories.models

import net.wiringbits.models.DbEnum

/** `entryName` (the case name) is the value stored in the `user_tokens.token_type` column. */
enum UserTokenType extends DbEnum {
  case EmailVerification
  case ResetPassword
}

object UserTokenType {
  def withNameInsensitiveOption(name: String): Option[UserTokenType] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
