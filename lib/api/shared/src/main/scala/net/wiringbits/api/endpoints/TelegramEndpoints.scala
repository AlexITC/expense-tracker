package net.wiringbits.api.endpoints

import sttp.tapir.*
import sttp.tapir.AnyEndpoint

object TelegramEndpoints {

  private val webhookSecretHeader: EndpointIO.Header[String] =
    header[String]("X-Telegram-Bot-Api-Secret-Token").default("")

  val receiveUpdate: Endpoint[Unit, (String, String), Unit, Unit, Any] =
    endpoint.post
      .in("telegram" / "webhook")
      .in(webhookSecretHeader)
      .in(stringBody)
      .tag("Telegram")
      .summary("Telegram webhook receiver")

  def routes: List[AnyEndpoint] = List(receiveUpdate)
}
