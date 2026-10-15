package net.wiringbits.actions.mcp

import net.wiringbits.mcp.McpTokenHasher
import net.wiringbits.repositories.McpRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class AuthenticateMcpTokenAction @Inject() (mcpRepository: McpRepository)(implicit ec: ExecutionContext) {

  def apply(bearerToken: Option[String]): Future[Option[UUID]] = bearerToken match {
    case None => Future.successful(None)
    case Some(token) =>
      val tokenHash = McpTokenHasher.hashHex(token)
      mcpRepository.findByTokenHash(tokenHash).map(_.map(_.userId))
  }
}
