package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.bankstatements.LinkRowToExpense
import net.wiringbits.api.models.expenses.{CreateExpense, UpdateExpense}
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.repositories.models.BankStatementRow
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class ExpensesControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

  private val tokenGenerator = mock[TokenGenerator]
  private val emailApi = mock[EmailApi]
  when(emailApi.sendEmail(any[EmailRequest]())).thenReturn(Future.unit)
  private val captchaApi = mock[ReCaptchaApi]
  when(captchaApi.verify(any[Captcha]())).thenReturn(Future.successful(true))

  override def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    super
      .guiceApplicationBuilder(container)
      .overrides(
        inject.bind[EmailApi].to(emailApi),
        inject.bind[ReCaptchaApi].to(captchaApi),
        inject.bind[TokenGenerator].to(tokenGenerator)
      )

  private val sampleDate = LocalDate.of(2024, 1, 15)

  private def createRequest(
      amount: BigDecimal = BigDecimal("50.00"),
      date: LocalDate = sampleDate,
      splits: List[CreateExpense.Split] = Nil
  ) = CreateExpense.Request(
    amount = amount,
    date = date,
    time = None,
    merchantId = None,
    notes = None,
    splits = splits
  )

  "GET /expenses" should {
    "return an empty list when user has no expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.getExpenses.futureValue
      response.data must be(empty)
    }

    "return the user's expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      client.createExpense(createRequest(BigDecimal("10.00"))).futureValue
      client.createExpense(createRequest(BigDecimal("20.00"))).futureValue

      val response = client.getExpenses.futureValue
      response.data.length must be(2)
    }

    "not return another user's expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createExpense(createRequest()).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      client2.getExpenses.futureValue.data must be(empty)
    }

    "return only expenses within the requested date range" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createExpense(createRequest(date = LocalDate.of(2024, 1, 9))).futureValue
      client.createExpense(createRequest(date = LocalDate.of(2024, 1, 10))).futureValue
      client.createExpense(createRequest(date = LocalDate.of(2024, 1, 21))).futureValue

      val response = client
        .getExpensesFiltered(Some(LocalDate.of(2024, 1, 10)), Some(LocalDate.of(2024, 1, 20)), unlinkedOnly = false)
        .futureValue
      response.data.map(_.date) must be(List(LocalDate.of(2024, 1, 10)))
    }

    "omit row-linked expenses only when unlinkedOnly is set" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val linkedId = client.createExpense(createRequest()).futureValue.id
      val unlinkedId = client.createExpense(createRequest()).futureValue.id
      val userId = client.currentUser.futureValue.id
      val rowId = UUID.randomUUID()
      val now = Instant.now()
      val stmtId = createBankStatementWithRows(
        userId,
        sid =>
          List(
            BankStatementRow.Create(
              rowId,
              sid,
              0,
              sampleDate,
              BigDecimal("-50.00"),
              "Test row",
              manuallyEdited = false,
              now,
              now,
              txnType = "purchase"
            )
          )
      )
      client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(linkedId)).futureValue

      client.getExpensesFiltered(None, None, unlinkedOnly = true).futureValue.data.map(_.id) must be(
        List(unlinkedId)
      )
      client.getExpensesFiltered(None, None, unlinkedOnly = false).futureValue.data.map(_.id).toSet must be(
        Set(linkedId, unlinkedId)
      )
    }
  }

  "POST /expenses" should {
    "create an expense with no splits" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.createExpense(createRequest(BigDecimal("75.00"))).futureValue
      response.amount must be(BigDecimal("75.00"))
      response.date must be(sampleDate)
    }

    "create an expense with splits summing to less than total" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val splits = List(CreateExpense.Split(cat.id, BigDecimal("30.00")))
      val response = client.createExpense(createRequest(BigDecimal("50.00"), splits = splits)).futureValue
      response.amount must be(BigDecimal("50.00"))
    }

    "create an expense with splits summing exactly to total" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val splits = List(CreateExpense.Split(cat.id, BigDecimal("50.00")))
      client.createExpense(createRequest(BigDecimal("50.00"), splits = splits)).futureValue
    }

    "create an expense with a merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant =
        client
          .createMerchant(net.wiringbits.api.models.merchants.CreateMerchant.Request("Amazon", None, None))
          .futureValue

      val req = CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, Some(merchant.id), None, Nil)
      val response = client.createExpense(req).futureValue
      val expenses = client.getExpenses.futureValue
      expenses.data.head.merchantName must be(Some("Amazon"))
    }

    "fail when amount is zero" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createExpense(createRequest(BigDecimal("0"))).expectError
      error must be("Amount must be greater than 0")
    }

    "fail when notes exceed 255 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val req = CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, Some("a" * 256), Nil)
      val error = client.createExpense(req).expectError
      error must be("Notes must be 255 characters or less")
    }

    "fail when amount is negative" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createExpense(createRequest(BigDecimal("-1"))).expectError
      error must be("Amount must be greater than 0")
    }

    "fail when split amount is zero" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val error = client
        .createExpense(createRequest(BigDecimal("50.00"), splits = List(CreateExpense.Split(cat.id, BigDecimal("0")))))
        .expectError
      error must be("Split amount must be greater than 0")
    }

    "fail when split sum exceeds expense amount" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val error = client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = List(CreateExpense.Split(cat.id, BigDecimal("60.00"))))
        )
        .expectError
      error must be("Sum of split amounts exceeds expense amount")
    }

    "fail when duplicate category in splits" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val splits =
        List(CreateExpense.Split(cat.id, BigDecimal("20.00")), CreateExpense.Split(cat.id, BigDecimal("10.00")))
      val error = client.createExpense(createRequest(BigDecimal("50.00"), splits = splits)).expectError
      error must be("Duplicate category in splits")
    }

    "fail when merchant belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val merchant =
        client2
          .createMerchant(net.wiringbits.api.models.merchants.CreateMerchant.Request("Shop", None, None))
          .futureValue

      val req = CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, Some(merchant.id), None, Nil)
      val error = client.createExpense(req).expectError
      error must be("Merchant not found")
    }

    "fail when category belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val cat = client2.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val error = client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = List(CreateExpense.Split(cat.id, BigDecimal("10.00"))))
        )
        .expectError
      error must be("Category not found")
    }
  }

  "PUT /expenses/:id" should {
    "update all fields" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest(BigDecimal("50.00"))).futureValue

      val newDate = LocalDate.of(2024, 6, 1)
      client
        .updateExpense(created.id, UpdateExpense.Request(BigDecimal("99.00"), newDate, None, None, Some("lunch"), Nil))
        .futureValue
      val expenses = client.getExpenses.futureValue
      val updated = expenses.data.find(_.id == created.id).get
      updated.amount must be(BigDecimal("99.00"))
      updated.date must be(newDate)
      updated.notes must be(Some("lunch"))
    }

    "replace split set on update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat1 = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue
      val cat2 =
        client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Transport")).futureValue
      val created = client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = List(CreateExpense.Split(cat1.id, BigDecimal("30.00"))))
        )
        .futureValue

      client
        .updateExpense(
          created.id,
          UpdateExpense.Request(
            BigDecimal("50.00"),
            sampleDate,
            None,
            None,
            None,
            List(UpdateExpense.Split(cat2.id, BigDecimal("20.00")))
          )
        )
        .futureValue

      val expenses = client.getExpenses.futureValue
      val updated = expenses.data.find(_.id == created.id).get
      updated.splits.length must be(1)
      updated.splits.head.categoryId must be(cat2.id)
    }

    "clear splits on update with empty list" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue
      val created = client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = List(CreateExpense.Split(cat.id, BigDecimal("30.00"))))
        )
        .futureValue

      client
        .updateExpense(created.id, UpdateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
      val expenses = client.getExpenses.futureValue
      expenses.data.find(_.id == created.id).get.splits must be(empty)
    }

    "fail when split sum exceeds new amount" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue
      val created = client.createExpense(createRequest(BigDecimal("100.00"))).futureValue

      val error = client
        .updateExpense(
          created.id,
          UpdateExpense.Request(
            BigDecimal("10.00"),
            sampleDate,
            None,
            None,
            None,
            List(UpdateExpense.Split(cat.id, BigDecimal("20.00")))
          )
        )
        .expectError
      error must be("Sum of split amounts exceeds expense amount")
    }

    "fail when expense belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest()).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2
        .updateExpense(created.id, UpdateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .expectError
      error must be("Expense not found")
    }
  }

  "DELETE /expenses/:id" should {
    "delete an expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest()).futureValue

      client.deleteExpense(created.id).futureValue
      client.getExpenses.futureValue.data must be(empty)
    }

    "fail when expense belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest()).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.deleteExpense(created.id).expectError
      error must be("Expense not found")
    }

    "fail when expense does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.deleteExpense(UUID.randomUUID()).expectError
      error must be("Expense not found")
    }
  }

  "category delete" should {
    "remove related splits but leave expense intact" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue
      client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = List(CreateExpense.Split(cat.id, BigDecimal("30.00"))))
        )
        .futureValue

      client.deleteCategory(cat.id).futureValue

      val expenses = client.getExpenses.futureValue
      expenses.data.length must be(1)
      expenses.data.head.splits must be(empty)
    }
  }

  "merchant delete" should {
    "set merchant_id to null on referencing expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant =
        client
          .createMerchant(net.wiringbits.api.models.merchants.CreateMerchant.Request("Amazon", None, None))
          .futureValue
      val req = CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, Some(merchant.id), None, Nil)
      client.createExpense(req).futureValue

      client.deleteMerchant(merchant.id).futureValue

      val expenses = client.getExpenses.futureValue
      expenses.data.head.merchantId must be(None)
      expenses.data.head.merchantName must be(None)
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getExpenses.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }

  private def buildClient() = {
    import sttp.client3.HttpClientFutureBackend
    import net.wiringbits.api.ApiClient
    implicit val sttpBackend: sttp.client3.SttpBackend[scala.concurrent.Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }
}
