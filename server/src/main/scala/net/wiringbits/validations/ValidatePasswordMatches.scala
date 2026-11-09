package net.wiringbits.validations

import net.wiringbits.common.models.Password
import net.wiringbits.repositories.models.User
import net.wiringbits.util.PasswordHasher

object ValidatePasswordMatches {
  def apply(maybe: Option[User], password: Password, passwordHasher: PasswordHasher): User = {
    maybe
      .filter(user => passwordHasher.check(password = password.string, hashedPassword = user.hashedPassword))
      .getOrElse(throw new RuntimeException("The given email/password doesn't match"))
  }
}
