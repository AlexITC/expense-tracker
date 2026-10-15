package net.wiringbits.api.endpoints

import sttp.model.StatusCode
import sttp.tapir.*

object McpEndpoints {

  // MCP Streamable HTTP transport (spec revision 2025-11-25): a single POST endpoint carrying one
  // JSON-RPC 2.0 message per request/response. Auth is a bearer token (see docs/guide — MCP API token),
  // not the session cookie used by the rest of the API, so this does not use `userAuth`/`authHandler`.
  val handle: Endpoint[Option[String], (Option[String], String), (StatusCode, String), (StatusCode, String), Any] =
    endpoint.post
      .in("mcp")
      .securityIn(auth.bearer[Option[String]]())
      .in(header[Option[String]]("MCP-Protocol-Version"))
      .in(stringJsonBody)
      .out(statusCode)
      .out(stringJsonBody)
      .errorOut(statusCode)
      .errorOut(stringJsonBody)
      .tag("MCP")
      .summary("MCP JSON-RPC endpoint")
      .description("Streamable HTTP transport. This server is stateless: no session id, no SSE stream.")

  // The Streamable HTTP transport allows an optional GET for server-initiated SSE streaming; this server
  // doesn't support that, so it responds 405 rather than the default 404 for an unregistered route.
  val streamNotSupported: Endpoint[Unit, Unit, Unit, Unit, Any] =
    endpoint.get
      .in("mcp")
      .out(statusCode(StatusCode.MethodNotAllowed))
      .tag("MCP")
      .summary("SSE streaming is not supported by this server")

  def routes: List[AnyEndpoint] = List(handle, streamNotSupported)
}
