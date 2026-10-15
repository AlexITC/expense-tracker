package net.wiringbits.actions.users

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.users.CreateMcpToken
import net.wiringbits.common.models.McpApiToken
import net.wiringbits.mcp.McpTokenHasher
import net.wiringbits.repositories.McpRepository

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateMcpTokenAction @Inject() (
    mcpRepository: McpRepository,
    secureRandom: SecureRandom
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(userId: UUID): Future[CreateMcpToken.Response] = {
    val bytes = new Array[Byte](16)
    secureRandom.nextBytes(bytes)
    val token = bytes.map(b => f"${b & 0xff}%02x").mkString
    val tokenHash = McpTokenHasher.hashHex(token)
    mcpRepository
      .create(UuidCreator.getTimeOrderedEpoch(), userId, tokenHash, clock.instant())
      .map(_ => CreateMcpToken.Response(McpApiToken.trusted(token)))
  }
}
