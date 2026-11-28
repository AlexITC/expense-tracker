package net.wiringbits.actions.auth

import net.wiringbits.models.UserSession
import net.wiringbits.repositories.UsersRepository
import net.wiringbits.validations.ValidateSessionVersion

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ValidateSessionAction @Inject() (usersRepository: UsersRepository)(implicit ec: ExecutionContext) {

  // resolves to the session's user id only when the cookie's version matches the user's current one
  def apply(session: UserSession): Future[UUID] = {
    for {
      user <- usersRepository.find(session.userId)
      _ = ValidateSessionVersion(user.map(_.sessionVersion), session.version)
    } yield session.userId
  }
}
