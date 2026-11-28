package net.wiringbits.actions.auth

import io.scalaland.chimney.dsl.transformInto
import net.wiringbits.api.models.auth.Login
import net.wiringbits.apis.ReCaptchaApi
import net.wiringbits.models.UserSession
import net.wiringbits.repositories.UsersRepository
import net.wiringbits.util.PasswordHasher
import net.wiringbits.validations.{ValidateCaptcha, ValidatePasswordMatches, ValidateVerifiedUser}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class LoginAction @Inject() (
    captchaApi: ReCaptchaApi,
    usersRepository: UsersRepository,
    passwordHasher: PasswordHasher
)(implicit
    ec: ExecutionContext
) {
  // returns the user details plus the session to store in the cookie
  def apply(request: Login.Request): Future[(Login.Response, UserSession)] = {
    for {
      _ <- ValidateCaptcha(captchaApi, request.captcha)
      // the user is verified
      maybe <- usersRepository.find(request.email)
      _ = maybe.foreach(ValidateVerifiedUser.apply)

      // The password matches
      hashedPassword <- maybe.fold(Future.successful(Option.empty[String]))(user =>
        usersRepository.findHashedPassword(user.id)
      )
      user = ValidatePasswordMatches(maybe, hashedPassword, request.password, passwordHasher)
    } yield (user.transformInto[Login.Response], UserSession(user.id, user.sessionVersion))
  }
}
