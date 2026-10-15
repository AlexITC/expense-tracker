package net.wiringbits.actions.users

import net.wiringbits.repositories.TelegramRepository
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

import java.security.SecureRandom
import java.time.{Clock, Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CreateTelegramLinkTokenActionSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
  private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
  private val secureRandom = new SecureRandom()

  private def newAction(repo: TelegramRepository) =
    new CreateTelegramLinkTokenAction(repo, secureRandom)(using global, fixedClock)

  "CreateTelegramLinkTokenAction" should {
    "return a response with a 32-character token" in {
      val repo = mock[TelegramRepository]
      when(repo.createLinkToken(any[String](), any[UUID](), any[Instant]())).thenReturn(Future.unit)

      val response = newAction(repo)(UUID.randomUUID()).futureValue

      response.token.value.length must be(32)
    }

    "store the SHA-256 hash (64 chars), not the plaintext token" in {
      val repo = mock[TelegramRepository]
      val tokenCaptor = ArgumentCaptor.forClass(classOf[String])
      when(repo.createLinkToken(any[String](), any[UUID](), any[Instant]())).thenReturn(Future.unit)

      val response = newAction(repo)(UUID.randomUUID()).futureValue

      verify(repo).createLinkToken(tokenCaptor.capture(), any[UUID](), any[Instant]())
      val storedToken = tokenCaptor.getValue
      storedToken.length must be(64)
      storedToken must not equal response.token.value
    }

    "produce unique tokens on successive calls" in {
      val repo = mock[TelegramRepository]
      when(repo.createLinkToken(any[String](), any[UUID](), any[Instant]())).thenReturn(Future.unit)

      val action = newAction(repo)
      val userId = UUID.randomUUID()
      val token1 = action(userId).futureValue.token.value
      val token2 = action(userId).futureValue.token.value

      token1 must not equal token2
    }

    "set expires_at to 15 minutes from now" in {
      val repo = mock[TelegramRepository]
      val expiresAtCaptor = ArgumentCaptor.forClass(classOf[Instant])
      when(repo.createLinkToken(any[String](), any[UUID](), any[Instant]())).thenReturn(Future.unit)

      newAction(repo)(UUID.randomUUID()).futureValue

      verify(repo).createLinkToken(any[String](), any[UUID](), expiresAtCaptor.capture())
      expiresAtCaptor.getValue must be(fixedInstant.plus(15, ChronoUnit.MINUTES))
    }

    "pass the userId to the repository" in {
      val repo = mock[TelegramRepository]
      val userIdCaptor = ArgumentCaptor.forClass(classOf[UUID])
      when(repo.createLinkToken(any[String](), any[UUID](), any[Instant]())).thenReturn(Future.unit)

      val userId = UUID.randomUUID()
      newAction(repo)(userId).futureValue

      verify(repo).createLinkToken(any[String](), userIdCaptor.capture(), any[Instant]())
      userIdCaptor.getValue must be(userId)
    }
  }
}
