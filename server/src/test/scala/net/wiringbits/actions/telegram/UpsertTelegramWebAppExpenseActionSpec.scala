package net.wiringbits.actions.telegram

import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error
import net.wiringbits.config.WebAppConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.services.DocumentTelegramNotifier
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{FakeTelegramApi, RandomData, RepositoryUtils}

import java.time.{Clock, LocalDate}
import java.util.UUID
import scala.util.Try

class UpsertTelegramWebAppExpenseActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): UpsertTelegramWebAppExpenseAction =
    new UpsertTelegramWebAppExpenseAction(
      repositories.merchants,
      repositories.categories,
      repositories.expenses,
      repositories.receipts,
      repositories.telegramReceipt,
      new DocumentTelegramNotifier(
        repositories.receipts,
        repositories.telegramReceipt,
        new FakeTelegramApi()(using executionContext)
      )(using executionContext),
      WebAppConfig("https://app.example.com")
    )(using executionContext, Clock.systemUTC())

  private def upsert(
      userId: UUID,
      expenseId: UUID,
      receiptId: Option[UUID],
      amount: BigDecimal,
      merchantName: Option[String],
      category: Option[String]
  )(using repositories: RepositoryComponents): Either[Error, Unit] =
    newAction()(
      userId = userId,
      expenseId = expenseId,
      receiptId = receiptId,
      amount = amount,
      date = LocalDate.of(2026, 1, 15),
      merchantName = merchantName,
      category = category,
      notes = None
    ).futureValue

  "apply" should {
    "create the expense without a merchant when none is given" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expenseId = UUID.randomUUID()

      val result = upsert(
        userId = user.id,
        expenseId = expenseId,
        receiptId = None,
        amount = BigDecimal("10"),
        merchantName = None,
        category = None
      )

      result must be(Right(()))
      repositories.expenses.find(expenseId, user.id).futureValue.value.merchantId must be(None)
      repositories.merchants.all(user.id).futureValue must be(empty)
    }

    "treat a blank merchant name as no merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expenseId = UUID.randomUUID()

      val result = upsert(
        userId = user.id,
        expenseId = expenseId,
        receiptId = None,
        amount = BigDecimal("10"),
        merchantName = Some("   "),
        category = None
      )

      result must be(Right(()))
      repositories.expenses.find(expenseId, user.id).futureValue.value.merchantId must be(None)
      repositories.merchants.all(user.id).futureValue must be(empty)
    }

    "create the expense with a pending merchant when a merchant is given" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val expenseId = UUID.randomUUID()
        val merchantName = RandomData.randomString()

        val result = upsert(
          userId = user.id,
          expenseId = expenseId,
          receiptId = None,
          amount = BigDecimal("10"),
          merchantName = Some(merchantName),
          category = None
        )

        result must be(Right(()))
        val merchants = repositories.merchants.all(user.id).futureValue
        merchants.map(_.name) must be(List(merchantName))
        merchants.map(_.reviewed) must be(List(false))
        repositories.expenses.find(expenseId, user.id).futureValue.value.merchantId must be(Some(merchants.head.id))
    }

    "clear the merchant when updating an expense without a merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val expense = createExpense(user.id, merchantId = Some(merchant.id)).futureValue

      val result = upsert(
        userId = user.id,
        expenseId = expense.id,
        receiptId = None,
        amount = BigDecimal("20"),
        merchantName = None,
        category = None
      )

      result must be(Right(()))
      val updated = repositories.expenses.find(expense.id, user.id).futureValue.value
      updated.merchantId must be(None)
      updated.amount must be(BigDecimal("20"))
    }

    "set the merchant when updating an expense that had none" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id, merchantId = None).futureValue
      val merchantName = RandomData.randomString()

      val result = upsert(
        userId = user.id,
        expenseId = expense.id,
        receiptId = None,
        amount = BigDecimal("20"),
        merchantName = Some(merchantName),
        category = None
      )

      result must be(Right(()))
      val merchant = repositories.merchants.findByName(user.id, merchantName).futureValue.value
      repositories.expenses.find(expense.id, user.id).futureValue.value.merchantId must be(Some(merchant.id))
    }

    "link the receipt when creating an expense without a merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val expenseId = UUID.randomUUID()

      val result = upsert(
        userId = user.id,
        expenseId = expenseId,
        receiptId = Some(receipt.id),
        amount = BigDecimal("10"),
        merchantName = None,
        category = None
      )

      result must be(Right(()))
      repositories.receipts.find(receipt.id, user.id).futureValue.value.expenseId must be(Some(expenseId))
      repositories.expenses.find(expenseId, user.id).futureValue.value.merchantId must be(None)
    }

    "never modify another user's expense" in withRepositories() { implicit repositories =>
      val owner = createUser().futureValue
      val other = createUser().futureValue
      val merchant = createMerchant(owner.id).futureValue
      val expense = createExpense(owner.id, merchantId = Some(merchant.id)).futureValue
      val before = repositories.expenses.find(expense.id, owner.id).futureValue.value

      // The outcome for the other user is irrelevant here; only the owner's row matters.
      Try(
        newAction()(
          userId = other.id,
          expenseId = expense.id,
          receiptId = None,
          amount = BigDecimal("99"),
          date = LocalDate.of(2026, 1, 15),
          merchantName = Some(RandomData.randomString()),
          category = None,
          notes = None
        ).futureValue
      )

      repositories.expenses.find(expense.id, owner.id).futureValue.value must be(before)
    }

    "return Left when merchant name exceeds 100 characters" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val result = upsert(
        userId = user.id,
        expenseId = UUID.randomUUID(),
        receiptId = None,
        amount = BigDecimal("10"),
        merchantName = Some("x" * 101),
        category = None
      )

      result must be(Left(Error.Invalid("Merchant name must be 100 characters or less")))
    }

    "return Left when amount is zero" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val result = upsert(
        userId = user.id,
        expenseId = UUID.randomUUID(),
        receiptId = None,
        amount = BigDecimal("0"),
        merchantName = None,
        category = None
      )

      result must be(Left(Error.Invalid("Amount must be positive")))
    }

    "return Left when amount is negative" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val result = upsert(
        userId = user.id,
        expenseId = UUID.randomUUID(),
        receiptId = None,
        amount = BigDecimal("-5"),
        merchantName = None,
        category = None
      )

      result must be(Left(Error.Invalid("Amount must be positive")))
    }

    "return Left when category exceeds 25 characters" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val result = upsert(
        userId = user.id,
        expenseId = UUID.randomUUID(),
        receiptId = None,
        amount = BigDecimal("10"),
        merchantName = None,
        category = Some("x" * 26)
      )

      result must be(Left(Error.Invalid("Category name must be 25 characters or less")))
    }
  }
}
