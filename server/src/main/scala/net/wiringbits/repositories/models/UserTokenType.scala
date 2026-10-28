package net.wiringbits.repositories.models

/** `entryName` is the value stored in the `user_tokens.token_type` column. */
enum UserTokenType(val entryName: String) {
  case EmailVerification extends UserTokenType("EmailVerification")
  case ResetPassword extends UserTokenType("ResetPassword")
}

object UserTokenType {
  def withNameInsensitiveOption(name: String): Option[UserTokenType] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
