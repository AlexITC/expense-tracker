package controllers

import net.wiringbits.actions.mcp.AuthenticateMcpTokenAction
import net.wiringbits.api.endpoints.McpEndpoints
import net.wiringbits.mcp.{McpDispatcher, McpProtocol}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.model.StatusCode
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class McpController @Inject() (
    authenticateMcpTokenAction: AuthenticateMcpTokenAction,
    mcpDispatcher: McpDispatcher
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def authenticate(bearerToken: Option[String]): Future[Either[(StatusCode, String), UUID]] = {
    authenticateMcpTokenAction(bearerToken).map {
      case Some(userId) => Right(userId)
      case None =>
        val body = Json.stringify(McpProtocol.error(None, -32001, "Unauthorized"))
        Left((StatusCode.Unauthorized, body))
    }
  }

  private def handle(
      userId: UUID
  )(input: (Option[String], String)): Future[Either[(StatusCode, String), (StatusCode, String)]] = {
    val (protocolVersionHeader, body) = input
    protocolVersionHeader.filterNot(McpProtocol.SupportedVersions.contains) match {
      case Some(unsupported) =>
        val err = Json.stringify(McpProtocol.error(None, -32600, s"Unsupported MCP-Protocol-Version: $unsupported"))
        Future.successful(Right((StatusCode.BadRequest, err)))
      case None =>
        val message = McpProtocol.parse(body)
        mcpDispatcher(userId, message)
          .map(Right(_))
          .recover { case NonFatal(ex) =>
            logger.error(s"MCP dispatch failed: ${ex.getMessage}", ex)
            Right((StatusCode.Ok, Json.stringify(McpProtocol.error(None, -32603, "Internal error"))))
          }
    }
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      McpEndpoints.handle.serverSecurityLogic(authenticate).serverLogic(handle),
      McpEndpoints.streamNotSupported.serverLogic(_ => Future.successful(Right(())))
    )
  }
}
