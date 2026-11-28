package net.wiringbits.actions.users

import net.wiringbits.api.models.users.UpdatePassword
import net.wiringbits.models.UserSession
import net.wiringbits.repositories.UsersRepository
import net.wiringbits.util.PasswordHasher
import net.wiringbits.util.EmailMessage
import net.wiringbits.validations.ValidatePasswordMatches

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdatePasswordAction @Inject() (
    usersRepository: UsersRepository,
    passwordHasher: PasswordHasher
)(implicit ec: ExecutionContext) {

  // returns the session carrying the bumped version, every older session is revoked
  def apply(userId: UUID, request: UpdatePassword.Request): Future[UserSession] = {
    for {
      maybe <- usersRepository.find(userId)
      hashedPassword <- usersRepository.findHashedPassword(userId)
      user = ValidatePasswordMatches(maybe, hashedPassword, request.oldPassword, passwordHasher)
      newHashedPassword = passwordHasher.hash(request.newPassword.string)
      emailMessage = EmailMessage.updatePassword(user.name)
      newVersion <- usersRepository.updatePassword(userId, newHashedPassword, emailMessage)
    } yield UserSession(userId, newVersion)
  }
}
