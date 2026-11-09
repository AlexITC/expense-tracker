package net.wiringbits.actions.telegram

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.repositories.{CategoriesRepository, ExpensesRepository, MerchantsRepository, ReceiptRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

object GetTelegramWebAppPrefillAction {

  /** Which record to prefill the form from. */
  sealed trait Target
  object Target {
    case class Receipt(id: UUID) extends Target
    case class Expense(id: UUID) extends Target
  }

  /** Primitive view model consumed by the Telegram web app page (no repository models leak to the controller). */
  case class Prefill(
      prefillAmount: Option[String],
      prefillDate: Option[String],
      prefillMerchant: Option[String],
      prefillCategory: Option[String],
      prefillNotes: Option[String],
      merchants: List[String],
      categories: List[String],
      merchantDefaultCategories: Map[String, String],
      targetExpenseId: UUID,
      receiptId: Option[UUID]
  )
}

/** Gathers the data needed to prefill the Telegram web app expense form. Keeps the repository calls out of the
  * controller, which now only authenticates, routes, and renders.
  */
class GetTelegramWebAppPrefillAction @Inject() (
    receiptRepository: ReceiptRepository,
    expensesRepository: ExpensesRepository,
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository
)(implicit ec: ExecutionContext) {
  import GetTelegramWebAppPrefillAction.*

  def apply(userId: UUID, target: Target): Future[Either[String, Prefill]] = target match {
    case Target.Receipt(id) => forReceipt(userId = userId, id = id)
    case Target.Expense(id) => forExpense(userId = userId, id = id)
  }

  private def forReceipt(userId: UUID, id: UUID): Future[Either[String, Prefill]] =
    receiptRepository.find(id = id, userId = userId).flatMap {
      case None => Future.successful(Left("Receipt not found"))
      case Some(r) =>
        val targetExpenseId = UuidCreator.getTimeOrderedEpoch()
        for {
          merchants <- merchantsRepository.all(userId)
          cats <- categoriesRepository.all(userId)
          resolvedMerchant <- r.merchantId match {
            case None => Future.successful(None)
            case Some(mid) => merchantsRepository.find(id = mid, userId = userId)
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
          Right(
            Prefill(
              prefillAmount = r.amount.map(_.toString),
              prefillDate = r.date.map(_.toString),
              prefillMerchant = prefillMerchant,
              prefillCategory = prefillCategory,
              prefillNotes = None,
              merchants = merchants.map(_.name),
              categories = cats.map(_.name),
              merchantDefaultCategories = defaultCategories,
              targetExpenseId = targetExpenseId,
              receiptId = Some(id)
            )
          )
        }
    }

  private def forExpense(userId: UUID, id: UUID): Future[Either[String, Prefill]] =
    expensesRepository.find(id = id, userId = userId).flatMap {
      case None => Future.successful(Left("Expense not found"))
      case Some(e) =>
        for {
          merchants <- merchantsRepository.all(userId)
          cats <- categoriesRepository.all(userId)
          merchantName <- e.merchantId match {
            case None => Future.successful(None)
            case Some(mid) => merchantsRepository.find(id = mid, userId = userId).map(_.map(_.name))
          }
          splits <- expensesRepository.findSplitsByExpenseId(expenseId = id, userId = userId)
        } yield {
          val prefillCategory = splits.headOption
            .flatMap(s => cats.find(_.id == s.categoryId))
            .map(_.name)
          val defaultCategories = merchants
            .flatMap(m => m.categoryId.flatMap(cid => cats.find(_.id == cid)).map(c => m.name -> c.name))
            .toMap
          Right(
            Prefill(
              prefillAmount = Some(e.amount.toString),
              prefillDate = Some(e.date.toString),
              prefillMerchant = merchantName,
              prefillCategory = prefillCategory,
              prefillNotes = e.notes,
              merchants = merchants.map(_.name),
              categories = cats.map(_.name),
              merchantDefaultCategories = defaultCategories,
              targetExpenseId = id,
              receiptId = None
            )
          )
        }
    }
}
