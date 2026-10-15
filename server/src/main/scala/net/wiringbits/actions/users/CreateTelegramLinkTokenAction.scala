package net.wiringbits.actions.users

import net.wiringbits.api.models.users.CreateTelegramLinkToken
import net.wiringbits.common.models.TelegramLinkToken
import net.wiringbits.repositories.TelegramRepository

import java.security.{MessageDigest, SecureRandom}
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateTelegramLinkTokenAction @Inject() (
    telegramRepository: TelegramRepository,
    secureRandom: SecureRandom
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(userId: UUID): Future[CreateTelegramLinkToken.Response] = {
    val bytes = new Array[Byte](16)
    secureRandom.nextBytes(bytes)
    val token = bytes.map(b => f"${b & 0xff}%02x").mkString
    val tokenHash = hashHex(token)
    val expiresAt = clock.instant().plus(15, ChronoUnit.MINUTES)
    telegramRepository
      .createLinkToken(tokenHash, userId, expiresAt)
      .map(_ => CreateTelegramLinkToken.Response(TelegramLinkToken.trusted(token)))
  }

  private def hashHex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
}
