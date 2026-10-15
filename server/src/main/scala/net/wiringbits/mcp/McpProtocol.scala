package net.wiringbits.mcp

import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

object McpProtocol {

  val ProtocolVersion = "2025-11-25"
  // 2024-11-05 and 2025-03-26 allowed JSON-RPC batching; this server only accepts a single message
  // per request (see `parse` below), so only versions where batching was dropped are advertised.
  val SupportedVersions: Set[String] = Set("2025-06-18", "2025-11-25")
  val ServerName = "expense-tracker"
  val ServerVersion = "1.0.0"

  sealed trait JsonRpcMessage
  final case class Request(id: JsValue, method: String, params: JsObject) extends JsonRpcMessage
  final case class Notification(method: String, params: JsObject) extends JsonRpcMessage
  case object ResponseMessage extends JsonRpcMessage
  final case class Malformed(code: Int, message: String) extends JsonRpcMessage

  def parse(body: String): JsonRpcMessage = {
    Try(Json.parse(body)) match {
      case Failure(_) => Malformed(-32700, "Parse error")
      case Success(obj: JsObject) =>
        (obj \ "jsonrpc").asOpt[String] match {
          case Some("2.0") =>
            (obj \ "method").asOpt[String] match {
              case Some(method) =>
                val params = (obj \ "params").asOpt[JsObject].getOrElse(Json.obj())
                (obj \ "id").toOption match {
                  case Some(id) => Request(id, method, params)
                  case None => Notification(method, params)
                }
              case None =>
                if ((obj \ "result").toOption.isDefined || (obj \ "error").toOption.isDefined) ResponseMessage
                else Malformed(-32600, "Invalid Request")
            }
          case _ => Malformed(-32600, "Invalid Request")
        }
      case Success(_) => Malformed(-32600, "Invalid Request")
    }
  }

  def success(id: JsValue, result: JsObject): JsValue =
    Json.obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

  def error(id: Option[JsValue], code: Int, message: String): JsValue =
    Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id.getOrElse(JsNull),
      "error" -> Json.obj("code" -> code, "message" -> message)
    )
}
