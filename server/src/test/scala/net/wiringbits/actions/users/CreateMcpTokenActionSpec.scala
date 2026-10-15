package net.wiringbits.actions.users

import net.wiringbits.repositories.McpRepository
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

import java.security.SecureRandom
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CreateMcpTokenActionSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
  private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
  private val secureRandom = new SecureRandom()

  private def newAction(repo: McpRepository) =
    new CreateMcpTokenAction(repo, secureRandom)(using global, fixedClock)

  "CreateMcpTokenAction" should {
    "return a response with a 32-character token" in {
      val repo = mock[McpRepository]
      when(repo.create(any[UUID](), any[UUID](), any[String](), any[Instant]())).thenReturn(Future.unit)

      val response = newAction(repo)(UUID.randomUUID()).futureValue

      response.token.value.length must be(32)
    }

    "store the SHA-256 hash (64 chars), not the plaintext token" in {
      val repo = mock[McpRepository]
      val tokenCaptor = ArgumentCaptor.forClass(classOf[String])
      when(repo.create(any[UUID](), any[UUID](), any[String](), any[Instant]())).thenReturn(Future.unit)

      val response = newAction(repo)(UUID.randomUUID()).futureValue

      verify(repo).create(any[UUID](), any[UUID](), tokenCaptor.capture(), any[Instant]())
      val storedToken = tokenCaptor.getValue
      storedToken.length must be(64)
      storedToken must not equal response.token.value
    }

    "produce unique tokens on successive calls" in {
      val repo = mock[McpRepository]
      when(repo.create(any[UUID](), any[UUID](), any[String](), any[Instant]())).thenReturn(Future.unit)

      val action = newAction(repo)
      val userId = UUID.randomUUID()
      val token1 = action(userId).futureValue.token.value
      val token2 = action(userId).futureValue.token.value

      token1 must not equal token2
    }

    "pass the userId to the repository" in {
      val repo = mock[McpRepository]
      val userIdCaptor = ArgumentCaptor.forClass(classOf[UUID])
      when(repo.create(any[UUID](), any[UUID](), any[String](), any[Instant]())).thenReturn(Future.unit)

      val userId = UUID.randomUUID()
      newAction(repo)(userId).futureValue

      verify(repo).create(any[UUID](), userIdCaptor.capture(), any[String](), any[Instant]())
      userIdCaptor.getValue must be(userId)
    }

    "pass the current time as createdAt" in {
      val repo = mock[McpRepository]
      val createdAtCaptor = ArgumentCaptor.forClass(classOf[Instant])
      when(repo.create(any[UUID](), any[UUID](), any[String](), any[Instant]())).thenReturn(Future.unit)

      newAction(repo)(UUID.randomUUID()).futureValue

      verify(repo).create(any[UUID](), any[UUID](), any[String](), createdAtCaptor.capture())
      createdAtCaptor.getValue must be(fixedInstant)
    }
  }
}
