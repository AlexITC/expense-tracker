package net.wiringbits.validations

import net.wiringbits.common.models.Password
import net.wiringbits.repositories.models.User
import net.wiringbits.services.PasswordHasher

// TODO(layering): password hashing is shared infra, not a service; this is the only
// validations -> services edge. It becomes validations -> shared once PasswordHasher's
// util move (PR #103) reaches this branch. See docs/guide/architecture.md
object ValidatePasswordMatches {
  def apply(maybe: Option[User], password: Password, passwordHasher: PasswordHasher): User = {
    maybe
      .filter(user => passwordHasher.check(password = password.string, hashedPassword = user.hashedPassword))
      .getOrElse(throw new RuntimeException("The given email/password doesn't match"))
  }
}
