package net.wiringbits.common.models

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Reads, Writes}

opaque type McpApiToken = String

object McpApiToken {
  def trusted(value: String): McpApiToken = value

  extension (t: McpApiToken) def value: String = t

  implicit val format: Format[McpApiToken] = Format(
    Reads[McpApiToken] {
      case JsString(s) => JsSuccess(trusted(s))
      case _ => JsError("error.expected.jsstring")
    },
    Writes[McpApiToken](t => JsString(t.value))
  )
}
