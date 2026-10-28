package net.wiringbits.actions.telegram

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error
import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.WebAppConfig
import net.wiringbits.repositories.models.{Expense, ExpenseCategorySplit}
import net.wiringbits.repositories.{
  CategoriesRepository,
  ExpensesRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramReceiptRepository
}
import org.slf4j.LoggerFactory

import java.time.{Clock, LocalDate, LocalTime}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

object UpsertTelegramWebAppExpenseAction {
  sealed trait Error
  object Error {
    case class Invalid(message: String) extends Error
  }
}

class UpsertTelegramWebAppExpenseAction @Inject() (
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository,
    expensesRepository: ExpensesRepository,
    receiptRepository: ReceiptRepository,
    telegramReceiptRepository: TelegramReceiptRepository,
    telegramApi: TelegramApi,
    webAppConfig: WebAppConfig
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(
      userId: UUID,
      expenseId: UUID,
      receiptId: Option[UUID],
      amount: BigDecimal,
      date: LocalDate,
      merchantName: String,
      category: Option[String],
      notes: Option[String]
  ): Future[Either[Error, Unit]] =
    validate(merchantName, amount, category, notes) match {
      case Left(msg) => Future.successful(Left(Error.Invalid(msg)))
      case Right(()) =>
        expensesRepository.find(expenseId, userId).flatMap {
          case None =>
            for {
              merchantId <- merchantsRepository.createPendingIfAbsent(userId, merchantName)
              catIdOpt <- resolveCategory(userId, category)
              _ <- createExpense(userId, expenseId, amount, date, merchantId, catIdOpt, notes)
              _ <- receiptId.fold(Future.unit)(rid => receiptRepository.linkToExpense(rid, userId, expenseId))
              _ <- notifyTelegram(expenseId, merchantName, amount, date)
            } yield Right(())
          case Some(existing) =>
            for {
              merchantId <- merchantsRepository.createPendingIfAbsent(userId, merchantName)
              catIdOpt <- resolveCategory(userId, category)
              splits <- buildUpdateSplits(userId, expenseId, catIdOpt, amount)
              _ <- expensesRepository
                .update(expenseId, userId, amount, date, existing.time, Some(merchantId), notes, splits)
              _ <- notifyTelegram(expenseId, merchantName, amount, date)
            } yield Right(())
        }
    }

  private def validate(
      merchantName: String,
      amount: BigDecimal,
      category: Option[String],
      notes: Option[String]
  ): Either[String, Unit] =
    if (merchantName.isEmpty) Left("Merchant name must not be empty")
    else if (merchantName.length > 100) Left("Merchant name must be 100 characters or less")
    else if (category.exists(_.length > 25)) Left("Category name must be 25 characters or less")
    else if (notes.exists(_.length > 500)) Left("Note must be 500 characters or less")
    else if (amount <= 0) Left("Amount must be positive")
    else Right(())

  private def resolveCategory(userId: UUID, category: Option[String]): Future[Option[UUID]] =
    category match {
      case None => Future.successful(None)
      case Some(name) => categoriesRepository.getOrCreate(userId, name).map(c => Some(c.id))
    }

  private def createExpense(
      userId: UUID,
      expenseId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      merchantId: UUID,
      catIdOpt: Option[UUID],
      notes: Option[String]
  ): Future[Unit] = {
    val now = clock.instant()
    val expense = Expense.Create(
      id = expenseId,
      userId = userId,
      amount = amount,
      date = date,
      time = Some(LocalTime.now(clock)),
      merchantId = Some(merchantId),
      notes = notes,
      createdAt = now,
      updatedAt = now
    )
    val splits = catIdOpt.toList.map { catId =>
      ExpenseCategorySplit.Create(UuidCreator.getTimeOrderedEpoch(), userId, expenseId, catId, amount)
    }
    expensesRepository.create(expense, splits)
  }

  private def buildUpdateSplits(
      userId: UUID,
      expenseId: UUID,
      catIdOpt: Option[UUID],
      amount: BigDecimal
  ): Future[List[ExpenseCategorySplit.Create]] =
    catIdOpt match {
      case Some(catId) =>
        Future.successful(
          List(ExpenseCategorySplit.Create(UuidCreator.getTimeOrderedEpoch(), userId, expenseId, catId, amount))
        )
      case None =>
        expensesRepository.findSplitsByExpenseId(expenseId, userId).map { existingSplits =>
          val oldTotal = existingSplits.foldLeft(BigDecimal(0))(_ + _.amount)
          existingSplits.map { s =>
            val newAmount =
              if (oldTotal == 0) s.amount
              else (s.amount / oldTotal * amount).setScale(2, BigDecimal.RoundingMode.HALF_UP)
            ExpenseCategorySplit.Create(UuidCreator.getTimeOrderedEpoch(), userId, expenseId, s.categoryId, newAmount)
          }
        }
    }

  private def notifyTelegram(
      expenseId: UUID,
      merchantName: String,
      amount: BigDecimal,
      date: LocalDate
  ): Future[Unit] =
    telegramReceiptRepository
      .findByExpenseId(expenseId)
      .flatMap {
        case None => Future.unit
        case Some(tr) =>
          val text = s"Expense saved: $merchantName · $amount · $date"
          val editUrl = s"${webAppConfig.host}/api/telegram/webapp?expense=$expenseId"
          tr.expenseMessageId match {
            case None =>
              telegramApi
                .sendMessageWithWebAppButton(tr.chatId, text, editUrl, "Edit")
                .flatMap(msgId => telegramReceiptRepository.updateExpenseMessageId(tr.telegramReceiptId, msgId))
            case Some(messageId) =>
              telegramApi.editMessage(tr.chatId, messageId, text).recoverWith { case _ =>
                telegramApi
                  .sendMessageWithWebAppButton(tr.chatId, text, editUrl, "Edit")
                  .flatMap(msgId => telegramReceiptRepository.updateExpenseMessageId(tr.telegramReceiptId, msgId))
              }
          }
      }
      .recover { case e =>
        logger.warn(s"Failed to notify Telegram for expense $expenseId: ${e.getMessage}")
      }
}
