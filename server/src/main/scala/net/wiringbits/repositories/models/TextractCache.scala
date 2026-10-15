package net.wiringbits.repositories.models

import play.api.libs.json.JsValue

import java.time.Instant

case class TextractCache(
    sha256: String,
    apiType: String,
    response: JsValue,
    createdAt: Instant
)

object TextractCache {
  case class Create(sha256: String, apiType: String, response: JsValue, createdAt: Instant)
}
