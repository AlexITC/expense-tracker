package net.wiringbits.mcp

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsNumber, Json}

class McpProtocolSpec extends AnyWordSpec with Matchers {

  "parse" should {
    "parse a request with an id" in {
      val body = Json.stringify(Json.obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> "ping"))
      McpProtocol.parse(body) must be(McpProtocol.Request(JsNumber(1), "ping", Json.obj()))
    }

    "parse a notification without an id as a Notification, not a Request" in {
      val body = Json.stringify(Json.obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized"))
      McpProtocol.parse(body) must be(McpProtocol.Notification("notifications/initialized", Json.obj()))
    }

    "treat a message with no method but a result as a ResponseMessage" in {
      val body = Json.stringify(Json.obj("jsonrpc" -> "2.0", "id" -> 1, "result" -> Json.obj()))
      McpProtocol.parse(body) must be(McpProtocol.ResponseMessage)
    }

    "return a parse error for invalid JSON" in {
      McpProtocol.parse("not json") must be(McpProtocol.Malformed(-32700, "Parse error"))
    }

    "return an invalid request error for a JSON array body" in {
      McpProtocol.parse("[1,2,3]") must be(McpProtocol.Malformed(-32600, "Invalid Request"))
    }

    "return an invalid request error when jsonrpc is not \"2.0\"" in {
      val body = Json.stringify(Json.obj("jsonrpc" -> "1.0", "id" -> 1, "method" -> "ping"))
      McpProtocol.parse(body) must be(McpProtocol.Malformed(-32600, "Invalid Request"))
    }
  }

  "success" should {
    "echo the id and wrap the result" in {
      val json = McpProtocol.success(JsNumber(1), Json.obj("ok" -> true))
      (json \ "jsonrpc").as[String] must be("2.0")
      (json \ "id").as[Int] must be(1)
      (json \ "result" \ "ok").as[Boolean] must be(true)
    }
  }

  "error" should {
    "default the id to null when absent" in {
      val json = McpProtocol.error(None, -32700, "Parse error")
      (json \ "id").toOption.get.toString must be("null")
      (json \ "error" \ "code").as[Int] must be(-32700)
    }
  }
}
