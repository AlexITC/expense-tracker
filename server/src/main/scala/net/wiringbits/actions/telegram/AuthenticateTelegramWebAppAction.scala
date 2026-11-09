package net.wiringbits.actions.telegram

import net.wiringbits.config.TelegramConfig
import net.wiringbits.repositories.TelegramRepository
import play.api.libs.json.Json

import java.util.{HexFormat, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Resolves the acting user for the Telegram web app, either from a dev user id (dev mode) or from a signed Telegram
  * initData payload. Keeps the Telegram-account lookup out of the controller so the controller stays above the
  * repository layer.
  */
class AuthenticateTelegramWebAppAction @Inject() (
    telegramConfig: TelegramConfig,
    telegramRepository: TelegramRepository
)(implicit ec: ExecutionContext) {

  def apply(initDataParam: Option[String], devUserIdParam: Option[String]): Future[Either[String, UUID]] =
    if (telegramConfig.webAppDevMode) {
      devUserIdParam match {
        case None =>
          Future.successful(Left("webAppDevMode is enabled but ?dev_user_id= is missing"))
        case Some(raw) =>
          Try(UUID.fromString(raw)).fold(
            _ => Future.successful(Left("Invalid dev_user_id")),
            id => Future.successful(Right(id))
          )
      }
    } else {
      initDataParam match {
        case None =>
          Future.successful(Left("Missing init_data"))
        case Some(initData) =>
          validateInitData(initData) match {
            case Left(err) => Future.successful(Left(err))
            case Right(telegramUserId) =>
              telegramRepository.findBotUserByTelegramId(telegramUserId).map {
                case None => Left("Telegram account not linked")
                case Some(botUser) => Right(botUser.userId)
              }
          }
      }
    }

  private def validateInitData(initData: String): Either[String, Long] =
    try {
      val params: Map[String, String] = initData
        .split("&")
        .map { pair =>
          val idx = pair.indexOf('=')
          if (idx < 0) ("", pair)
          else
            (
              pair.substring(0, idx),
              java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            )
        }
        .toMap

      params.get("hash") match {
        case None => Left("Missing hash in initData")
        case Some(hash) =>
          val dataCheckString = params
            .removed("hash")
            .toSeq
            .sortBy(_._1)
            .map { case (k, v) => s"$k=$v" }
            .mkString("\n")

          val secretKey = hmacSha256("WebAppData".getBytes("UTF-8"), telegramConfig.botToken)
          val computedHash = HexFormat.of().formatHex(hmacSha256(secretKey, dataCheckString))

          if (computedHash != hash) Left("Invalid initData signature")
          else
            params.get("user") match {
              case None => Left("Missing user in initData")
              case Some(userJson) =>
                Try((Json.parse(userJson) \ "id").as[Long]).toEither.left
                  .map(e => s"Cannot read user.id from initData: ${e.getMessage}")
            }
      }
    } catch {
      case e: Exception => Left(s"Malformed initData: ${e.getMessage}")
    }

  private def hmacSha256(key: Array[Byte], data: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8"))
  }
}
