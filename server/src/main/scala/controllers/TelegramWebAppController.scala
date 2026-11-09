package controllers

import net.wiringbits.actions.telegram.GetTelegramWebAppPrefillAction.{Prefill, Target}
import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error as UpsertExpenseError
import net.wiringbits.actions.telegram.{
  AuthenticateTelegramWebAppAction,
  GetTelegramWebAppPrefillAction,
  UpsertTelegramWebAppExpenseAction
}
import net.wiringbits.config.TelegramConfig
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.*

import java.time.LocalDate
import java.util.UUID
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
    authenticateAction: AuthenticateTelegramWebAppAction,
    getPrefillAction: GetTelegramWebAppPrefillAction,
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
      authenticateAction(initDataParam = init_data, devUserIdParam = dev_user_id).flatMap {
        case Left(msg) =>
          Future.successful(Unauthorized(msg))
        case Right(userId) =>
          def render(result: Either[String, Prefill]): Result = result match {
            case Left(notFound) => NotFound(notFound)
            case Right(p) =>
              Ok(
                views.html.telegramWebApp(
                  initData = init_data.getOrElse(""),
                  prefillAmount = p.prefillAmount,
                  prefillDate = p.prefillDate,
                  prefillMerchant = p.prefillMerchant,
                  prefillCategory = p.prefillCategory,
                  prefillNotes = p.prefillNotes,
                  merchants = p.merchants,
                  categories = p.categories,
                  merchantDefaultCategories = p.merchantDefaultCategories,
                  targetExpenseId = p.targetExpenseId,
                  receiptId = p.receiptId,
                  devMode = telegramConfig.webAppDevMode
                )
              )
          }

          (receipt, expense) match {
            case (Some(receiptId), _) =>
              Try(UUID.fromString(receiptId)).toOption match {
                case None => Future.successful(BadRequest("Invalid receipt ID"))
                case Some(id) => getPrefillAction(userId, Target.Receipt(id)).map(render)
              }
            case (_, Some(expenseId)) =>
              Try(UUID.fromString(expenseId)).toOption match {
                case None => Future.successful(BadRequest("Invalid expense ID"))
                case Some(id) => getPrefillAction(userId, Target.Expense(id)).map(render)
              }
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
      authenticateAction(initDataParam = effectiveInitData, devUserIdParam = dev_user_id).flatMap {
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
                        userId = userId,
                        expenseId = expenseId,
                        receiptId = req.receiptId,
                        amount = req.amount,
                        date = req.date,
                        merchantName = req.merchantName,
                        category = req.category,
                        notes = req.notes
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
    } yield UpsertExpenseRequest(receiptId, amount, date, merchantName, category = category, notes = notes)
}
