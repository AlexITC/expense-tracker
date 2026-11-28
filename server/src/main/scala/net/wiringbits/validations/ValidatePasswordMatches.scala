package net.wiringbits.validations

import net.wiringbits.common.models.Password
import net.wiringbits.repositories.models.User
import net.wiringbits.util.PasswordHasher

object ValidatePasswordMatches {
  def apply(
      maybe: Option[User],
      hashedPassword: Option[String],
      password: Password,
      passwordHasher: PasswordHasher
  ): User = {
    maybe
      .filter(_ =>
        hashedPassword.exists(hashed => passwordHasher.check(password = password.string, hashedPassword = hashed))
      )
      .getOrElse(throw new RuntimeException("The given email/password doesn't match"))
  }
}
