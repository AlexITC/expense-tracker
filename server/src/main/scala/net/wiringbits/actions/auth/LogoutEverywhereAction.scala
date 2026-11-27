package net.wiringbits.actions.auth

import net.wiringbits.repositories.UsersRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class LogoutEverywhereAction @Inject() (
    usersRepository: UsersRepository
)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[Unit] = {
    usersRepository.logoutEverywhere(userId).map(_ => ())
  }
}
