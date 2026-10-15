package controllers

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction
import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error as UpsertExpenseError
import net.wiringbits.config.TelegramConfig
import net.wiringbits.repositories.{
  CategoriesRepository,
  ExpensesRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramRepository
}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.*

import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

private case class UpsertExpenseRequest(
    receiptId: Option[UUID],
    amount: BigDecimal,
    date: LocalDate,
    merchantName: String,
    category: Option[String],
    notes: Option[String]
)

class TelegramWebAppController @Inject() (
    cc: ControllerComponents,
    telegramConfig: TelegramConfig,
    telegramRepository: TelegramRepository,
    receiptRepository: ReceiptRepository,
    expensesRepository: ExpensesRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository,
    upsertExpenseAction: UpsertTelegramWebAppExpenseAction
)(implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  def index(
      receipt: Option[String] = None,
      expense: Option[String] = None,
      dev_user_id: Option[String] = None,
      init_data: Option[String] = None
  ): Action[AnyContent] = Action.async { _ =>
    if (!telegramConfig.webAppDevMode && init_data.isEmpty) {
      Future.successful(
        Ok(
          views.html.telegramWebApp(
            initData = "",
            prefillAmount = None,
            prefillDate = None,
            prefillMerchant = None,
            prefillCategory = None,
            prefillNotes = None,
            merchants = Nil,
            categories = Nil,
            merchantDefaultCategories = Map.empty,
            targetExpenseId = new UUID(0L, 0L),
            receiptId = None,
            devMode = false
          )
        )
      )
    } else {
      authenticate(init_data, dev_user_id).flatMap {
        case Left(msg) =>
          Future.successful(Unauthorized(msg))
        case Right(userId) =>
          (receipt, expense) match {
            case (Some(receiptId), _) =>
              Try(UUID.fromString(receiptId)).fold(
                _ => Future.successful(BadRequest("Invalid receipt ID")),
                id =>
                  receiptRepository.find(id, userId).flatMap {
                    case None => Future.successful(NotFound("Receipt not found"))
                    case Some(r) =>
                      val targetExpenseId = UuidCreator.getTimeOrderedEpoch()
                      for {
                        merchants <- merchantsRepository.all(userId)
                        cats <- categoriesRepository.all(userId)
                        resolvedMerchant <- r.merchantId match {
                          case None => Future.successful(None)
                          case Some(mid) => merchantsRepository.find(mid, userId)
                        }
                      } yield {
                        val prefillMerchant = resolvedMerchant.map(_.name).orElse(r.merchantName)
                        val prefillCategory = resolvedMerchant
                          .flatMap(_.categoryId)
                          .flatMap(catId => cats.find(_.id == catId))
                          .map(_.name)
                        val defaultCategories = merchants
                          .flatMap(m => m.categoryId.flatMap(cid => cats.find(_.id == cid)).map(c => m.name -> c.name))
                          .toMap
                        Ok(
                          views.html.telegramWebApp(
                            initData = init_data.getOrElse(""),
                            prefillAmount = r.amount.map(_.toString),
                            prefillDate = r.date.map(_.toString),
                            prefillMerchant = prefillMerchant,
                            prefillCategory = prefillCategory,
                            prefillNotes = None,
                            merchants = merchants.map(_.name),
                            categories = cats.map(_.name),
                            merchantDefaultCategories = defaultCategories,
                            targetExpenseId = targetExpenseId,
                            receiptId = Some(id),
                            devMode = telegramConfig.webAppDevMode
                          )
                        )
                      }
                  }
              )
            case (_, Some(expenseId)) =>
              Try(UUID.fromString(expenseId)).fold(
                _ => Future.successful(BadRequest("Invalid expense ID")),
                id =>
                  expensesRepository.find(id, userId).flatMap {
                    case None => Future.successful(NotFound("Expense not found"))
                    case Some(e) =>
                      for {
                        merchants <- merchantsRepository.all(userId)
                        cats <- categoriesRepository.all(userId)
                        merchantName <- e.merchantId match {
                          case None => Future.successful(None)
                          case Some(mid) => merchantsRepository.find(mid, userId).map(_.map(_.name))
                        }
                        splits <- expensesRepository.findSplitsByExpenseId(id, userId)
                      } yield {
                        val prefillCategory = splits.headOption
                          .flatMap(s => cats.find(_.id == s.categoryId))
                          .map(_.name)
                        val defaultCategories = merchants
                          .flatMap(m => m.categoryId.flatMap(cid => cats.find(_.id == cid)).map(c => m.name -> c.name))
                          .toMap
                        Ok(
                          views.html.telegramWebApp(
                            initData = init_data.getOrElse(""),
                            prefillAmount = Some(e.amount.toString),
                            prefillDate = Some(e.date.toString),
                            prefillMerchant = merchantName,
                            prefillCategory = prefillCategory,
                            prefillNotes = e.notes,
                            merchants = merchants.map(_.name),
                            categories = cats.map(_.name),
                            merchantDefaultCategories = defaultCategories,
                            targetExpenseId = id,
                            receiptId = None,
                            devMode = telegramConfig.webAppDevMode
                          )
                        )
                      }
                  }
              )
            case _ =>
              Future.successful(BadRequest("Either ?receipt= or ?expense= is required"))
          }
      }
    }
  }

  def upsertExpense(
      id: String,
      dev_user_id: Option[String] = None,
      init_data: Option[String] = None
  ): Action[AnyContent] = Action.async { request =>
    val effectiveInitData = init_data.orElse(request.headers.get("X-Telegram-Init-Data"))
    if (!telegramConfig.webAppDevMode && effectiveInitData.isEmpty) {
      Future.successful(Unauthorized("Missing authentication"))
    } else {
      authenticate(effectiveInitData, dev_user_id).flatMap {
        case Left(msg) => Future.successful(Unauthorized(msg))
        case Right(userId) =>
          Try(UUID.fromString(id)).fold(
            _ => Future.successful(BadRequest("Invalid expense ID")),
            expenseId =>
              request.body.asJson match {
                case None => Future.successful(BadRequest("Expected JSON body"))
                case Some(json) =>
                  parseUpsertExpenseBody(json) match {
                    case Left(err) => Future.successful(BadRequest(err))
                    case Right(req) =>
                      upsertExpenseAction(
                        userId,
                        expenseId,
                        req.receiptId,
                        req.amount,
                        req.date,
                        req.merchantName,
                        req.category,
                        req.notes
                      ).map {
                        case Left(UpsertExpenseError.Invalid(msg)) =>
                          UnprocessableEntity(Json.obj("error" -> msg))
                        case Right(_) => Ok(Json.obj("ok" -> true))
                      }
                  }
              }
          )
      }
    }
  }

  private def parseUpsertExpenseBody(json: JsValue): Either[String, UpsertExpenseRequest] =
    for {
      amount <- Try((json \ "amount").as[BigDecimal]).toEither.left.map(_ => "Invalid or missing amount")
      dateStr <- Try((json \ "date").as[String]).toEither.left.map(_ => "Invalid or missing date")
      date <- Try(LocalDate.parse(dateStr)).toEither.left.map(_ => "Invalid date format, expected YYYY-MM-DD")
      merchantName <- Try((json \ "merchantName").as[String]).toEither.left.map(_ => "Invalid or missing merchantName")
      receiptId = (json \ "receiptId").asOpt[String].flatMap(s => Try(UUID.fromString(s)).toOption)
      category = (json \ "category").asOpt[String]
      notes = (json \ "notes").asOpt[String]
    } yield UpsertExpenseRequest(receiptId, amount, date, merchantName, category, notes)

  private def authenticate(
      initDataParam: Option[String],
      devUserIdParam: Option[String]
  ): Future[Either[String, UUID]] =
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
          val computedHash = hmacSha256(secretKey, dataCheckString)
            .map(b => f"${b & 0xff}%02x")
            .mkString

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
