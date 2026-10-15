package net.wiringbits.common.models

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Reads, Writes}

opaque type TelegramLinkToken = String

object TelegramLinkToken {
  def trusted(value: String): TelegramLinkToken = value

  extension (t: TelegramLinkToken) def value: String = t

  implicit val format: Format[TelegramLinkToken] = Format(
    Reads[TelegramLinkToken] {
      case JsString(s) => JsSuccess(trusted(s))
      case _ => JsError("error.expected.jsstring")
    },
    Writes[TelegramLinkToken](t => JsString(t.value))
  )
}
