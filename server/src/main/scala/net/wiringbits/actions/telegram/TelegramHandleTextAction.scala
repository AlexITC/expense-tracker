package net.wiringbits.actions.telegram

import net.wiringbits.apis.TelegramApi
import net.wiringbits.repositories.TelegramRepository
import net.wiringbits.services.TelegramMessageParser
import org.slf4j.LoggerFactory

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class TelegramHandleTextAction @Inject() (
    telegramRepository: TelegramRepository,
    telegramApi: TelegramApi,
    telegramExpenseAction: TelegramExpenseAction
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(text: String, telegramUserId: Long, chatId: Long): Future[Unit] =
    telegramRepository.findBotUserByTelegramId(telegramUserId).flatMap {
      case None =>
        logger.info(s"Telegram text: unlinked user telegramUserId=$telegramUserId")
        telegramApi.sendMessage(
          chatId,
          "Account not linked. Generate a link token in the web app and send /link <token> here."
        )
      case Some(botUser) =>
        TelegramMessageParser.parse(text) match {
          case Left(err) =>
            logger.info(s"Telegram text: parse error telegramUserId=$telegramUserId err=$err")
            telegramApi.sendMessage(
              chatId,
              s"Could not parse: $err\n\nFormat (one per line):\namount\nmerchant\ncategory (optional)"
            )
          case Right(parsed) =>
            telegramExpenseAction(botUser.userId, parsed).flatMap {
              case Left(err) =>
                logger.info(s"Telegram text: expense failed userId=${botUser.userId} err=$err")
                telegramApi.sendMessage(chatId, s"Error: $err")
              case Right(expenseId) =>
                logger.info(s"Telegram text: expense created userId=${botUser.userId} expenseId=$expenseId")
                val categoryLine = parsed.category.map(c => s"\nCategory: $c").getOrElse("")
                val notesLine = parsed.notes.map(n => s"\nNote: $n").getOrElse("")
                telegramApi.sendMessage(
                  chatId,
                  s"Expense recorded!\nAmount: €${parsed.amount}\nMerchant: ${parsed.merchantName}$categoryLine$notesLine"
                )
            }
        }
    }
}
