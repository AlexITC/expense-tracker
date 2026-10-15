package net.wiringbits.actions.telegram

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.repositories.models.{Category, Expense, ExpenseCategorySplit, Merchant}
import net.wiringbits.repositories.{CategoriesRepository, ExpensesRepository, ImportRuleRepository, MerchantsRepository}
import net.wiringbits.services.TelegramMessageParser.ParsedMessage
import org.slf4j.LoggerFactory

import java.time.{Clock, LocalDate, LocalTime}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class TelegramExpenseAction @Inject() (
    merchantsRepository: MerchantsRepository,
    categoriesRepository: CategoriesRepository,
    expensesRepository: ExpensesRepository,
    importRuleRepository: ImportRuleRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, msg: ParsedMessage): Future[Either[String, UUID]] = {
    val safeName = msg.merchantName.replace('\n', ' ').replace('\r', ' ')
    logger.info(s"Telegram expense: userId=$userId merchant=$safeName amount=${msg.amount}")
    val normalizedName = ImportRuleMatcher.normalize(msg.merchantName)
    val date = LocalDate.now(clock)

    expensesRepository.countByMonth(userId, date.getYear, date.getMonthValue).flatMap { count =>
      if (count >= 500)
        Future.successful(Left("Maximum number of expenses for this month (500) reached"))
      else
        for {
          merchantByName <- merchantsRepository.findByName(userId, msg.merchantName)

          ruleResult <- merchantByName match {
            case Some(_) => Future.successful(Right(Option.empty[Merchant]))
            case None =>
              importRuleRepository.all(userId).flatMap { rules =>
                val matchRules = rules.map(r => ImportRuleMatcher.Rule(r.id, r.action, r.pattern, r.merchantId))
                ImportRuleMatcher.findRowWinner(matchRules, normalizedName) match {
                  case Some(w) if w.action == ImportRuleAction.Ignore =>
                    Future.successful(Left(s"Transaction skipped: matches an ignore rule for '$safeName'"))
                  case Some(w) if w.action == ImportRuleAction.SetMerchant =>
                    w.merchantId.fold(Future.successful(Right(Option.empty[Merchant])))(id =>
                      merchantsRepository.find(id, userId).map(Right(_))
                    )
                  case _ => Future.successful(Right(Option.empty[Merchant]))
                }
              }
          }

          outcome <- ruleResult match {
            case Left(err) => Future.successful(Left(err))
            case Right(merchantByRule) =>
              val knownMerchant = merchantByName.orElse(merchantByRule)
              knownMerchant match {
                case None =>
                  msg.category match {
                    case None =>
                      Future.successful(
                        Left(
                          s"Cannot create expense: merchant '${msg.merchantName}' not found and no category provided to create it"
                        )
                      )
                    case Some(catName) =>
                      if (msg.merchantName.length > 100)
                        Future.successful(Left("Merchant name must be 100 characters or less"))
                      else if (catName.length > 25)
                        Future.successful(Left("Category name must be 25 characters or less"))
                      else
                        merchantsRepository.count(userId).flatMap { merchantCount =>
                          if (merchantCount >= 200)
                            Future.successful(Left("Maximum number of merchants (200) reached"))
                          else
                            for {
                              catId <- findOrCreateCategory(userId, catName)
                              now = clock.instant()
                              merchantId = UuidCreator.getTimeOrderedEpoch()
                              _ <- merchantsRepository.create(
                                Merchant.Create(
                                  id = merchantId,
                                  userId = userId,
                                  name = msg.merchantName,
                                  notes = None,
                                  categoryId = Some(catId),
                                  taxId = None,
                                  reviewed = false,
                                  createdAt = now,
                                  updatedAt = now
                                )
                              )
                              expenseId <- buildExpense(userId, msg.amount, merchantId, Some(catId), date, msg.notes)
                            } yield Right(expenseId)
                        }
                  }

                case Some(merchant) =>
                  msg.category match {
                    case Some(catName) if catName.length > 25 =>
                      Future.successful(Left("Category name must be 25 characters or less"))
                    case catOpt =>
                      for {
                        catIdOpt <- catOpt match {
                          case Some(catName) => findOrCreateCategory(userId, catName).map(Some(_))
                          case None => Future.successful(merchant.categoryId)
                        }
                        expenseId <- buildExpense(userId, msg.amount, merchant.id, catIdOpt, date, msg.notes)
                      } yield Right(expenseId)
                  }
              }
          }
        } yield outcome
    }
  }

  private def findOrCreateCategory(userId: UUID, name: String): Future[UUID] =
    categoriesRepository.findByName(userId, name).flatMap {
      case Some(cat) => Future.successful(cat.id)
      case None =>
        val now = clock.instant()
        val catId = UuidCreator.getTimeOrderedEpoch()
        categoriesRepository.create(Category.Create(catId, userId, name, now, now)).map(_ => catId)
    }

  private def buildExpense(
      userId: UUID,
      amount: BigDecimal,
      merchantId: UUID,
      catIdOpt: Option[UUID],
      date: LocalDate,
      notes: Option[String]
  ): Future[UUID] = {
    val now = clock.instant()
    val expenseId = UuidCreator.getTimeOrderedEpoch()
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
    expensesRepository.create(expense, splits).map(_ => expenseId)
  }
}
