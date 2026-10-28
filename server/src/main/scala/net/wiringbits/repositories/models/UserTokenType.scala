package net.wiringbits.repositories.models

enum UserTokenType(val entryName: String) {
  case EmailVerification extends UserTokenType("EMAILVERIFICATION")
  case ResetPassword extends UserTokenType("RESETPASSWORD")
}

object UserTokenType {
  def withNameInsensitiveOption(name: String): Option[UserTokenType] =
    values.find(_.entryName.equalsIgnoreCase(name))
}
