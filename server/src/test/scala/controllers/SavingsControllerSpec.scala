package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.api.models.merchants.CreateMerchant
import net.wiringbits.api.models.savings.{
  CreateSavingsAllocation,
  CreateSavingsGoal,
  UpdateSavingsAllocation,
  UpdateSavingsGoal
}
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.Future

class SavingsControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  private val sampleDate = LocalDate.of(2024, 6, 1)

  "Savings goals CRUD and uniqueness" should {
    "create, list, update, and delete a goal" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val created =
        client.createSavingsGoal(CreateSavingsGoal.Request("Vacation", Some(BigDecimal("5000")))).futureValue
      created.id must not be null

      val listed = client.getSavingsGoals.futureValue
      listed.data.length must be(1)
      listed.data.head.name must be("Vacation")
      listed.data.head.targetAmount must be(Some(BigDecimal("5000")))
      listed.data.head.balance must be(BigDecimal(0))
      listed.data.head.progress must be(Some(BigDecimal(0)))

      client.updateSavingsGoal(created.id, UpdateSavingsGoal.Request("Holiday Fund", None)).futureValue

      val afterUpdate = client.getSavingsGoals.futureValue
      afterUpdate.data.head.name must be("Holiday Fund")
      afterUpdate.data.head.targetAmount must be(None)
      afterUpdate.data.head.progress must be(None)

      client.deleteSavingsGoal(created.id).futureValue
      client.getSavingsGoals.futureValue.data must be(empty)
    }

    "reject duplicate goal names (case-insensitive) for the same user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      client.createSavingsGoal(CreateSavingsGoal.Request("Vacation", None)).futureValue
      val error = client.createSavingsGoal(CreateSavingsGoal.Request("vacation", None)).expectError
      error must be("A savings goal with that name already exists")
    }

    "allow the same goal name for two different users" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createSavingsGoal(CreateSavingsGoal.Request("Vacation", None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val created2 = client2.createSavingsGoal(CreateSavingsGoal.Request("Vacation", None)).futureValue
      created2.id must not be null
    }

    "fail when name is blank" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createSavingsGoal(CreateSavingsGoal.Request("  ", None)).expectError
      error must be("The name is required")
    }

    "fail when target amount is zero or negative" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createSavingsGoal(CreateSavingsGoal.Request("Goal", Some(BigDecimal(0)))).expectError
      error must include("greater than zero")
    }
  }

  "Savings allocations CRUD and balance" should {
    "add allocations and track balance and progress" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val goal =
        client.createSavingsGoal(CreateSavingsGoal.Request("Emergency Fund", Some(BigDecimal("1000")))).futureValue

      val alloc1 = client
        .createSavingsAllocation(goal.id, CreateSavingsAllocation.Request(BigDecimal("500"), sampleDate, None, None))
        .futureValue
      val alloc2 = client
        .createSavingsAllocation(
          goal.id,
          CreateSavingsAllocation.Request(BigDecimal("200"), sampleDate, Some("bonus"), None)
        )
        .futureValue

      val goals = client.getSavingsGoals.futureValue
      goals.data.head.balance must be(BigDecimal("700"))
      goals.data.head.progress must be(Some(BigDecimal("0.7")))

      val allocations = client.getSavingsAllocations(goal.id).futureValue
      allocations.data.length must be(2)
      allocations.data.map(_.amount) must contain allOf (BigDecimal("500"), BigDecimal("200"))

      client
        .updateSavingsAllocation(
          goal.id,
          alloc1.id,
          UpdateSavingsAllocation.Request(BigDecimal("300"), sampleDate, None, None)
        )
        .futureValue
      val afterUpdate = client.getSavingsGoals.futureValue
      afterUpdate.data.head.balance must be(BigDecimal("500"))

      client.deleteSavingsAllocation(goal.id, alloc1.id).futureValue
      val afterDelete = client.getSavingsGoals.futureValue
      afterDelete.data.head.balance must be(BigDecimal("200"))

      client.getSavingsAllocations(goal.id).futureValue.data.length must be(1)
    }

    "support negative allocations (withdrawals)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val goal = client.createSavingsGoal(CreateSavingsGoal.Request("Fund", None)).futureValue
      client
        .createSavingsAllocation(goal.id, CreateSavingsAllocation.Request(BigDecimal("400"), sampleDate, None, None))
        .futureValue
      client
        .createSavingsAllocation(
          goal.id,
          CreateSavingsAllocation.Request(BigDecimal("-100"), sampleDate, Some("withdrawal"), None)
        )
        .futureValue

      val goals = client.getSavingsGoals.futureValue
      goals.data.head.balance must be(BigDecimal("300"))
    }

    "fail when amount is zero" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val goal = client.createSavingsGoal(CreateSavingsGoal.Request("Fund", None)).futureValue
      val error = client
        .createSavingsAllocation(goal.id, CreateSavingsAllocation.Request(BigDecimal(0), sampleDate, None, None))
        .expectError
      error must be("Amount must not be zero")
    }

    "fail when goal does not belong to user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val goal = client.createSavingsGoal(CreateSavingsGoal.Request("Fund", None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2
        .createSavingsAllocation(goal.id, CreateSavingsAllocation.Request(BigDecimal("100"), sampleDate, None, None))
        .expectError
      error must be("Savings goal not found")
    }
  }

  "Cascade delete" should {
    "delete all allocations when a goal is deleted" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val goal = client.createSavingsGoal(CreateSavingsGoal.Request("Temp Goal", None)).futureValue
      client
        .createSavingsAllocation(goal.id, CreateSavingsAllocation.Request(BigDecimal("100"), sampleDate, None, None))
        .futureValue
      client
        .createSavingsAllocation(goal.id, CreateSavingsAllocation.Request(BigDecimal("200"), sampleDate, None, None))
        .futureValue

      client.deleteSavingsGoal(goal.id).futureValue

      client.getSavingsGoals.futureValue.data must be(empty)
    }
  }

  "Savings estimate" should {
    "return the median expense amount for a merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val merchant = client.createMerchant(CreateMerchant.Request("Supermarket", None, None, None)).futureValue
      val merchantId = merchant.id

      client
        .createExpense(CreateExpense.Request(BigDecimal("100"), sampleDate, None, Some(merchantId), None, Nil))
        .futureValue
      client
        .createExpense(CreateExpense.Request(BigDecimal("200"), sampleDate, None, Some(merchantId), None, Nil))
        .futureValue
      client
        .createExpense(CreateExpense.Request(BigDecimal("300"), sampleDate, None, Some(merchantId), None, Nil))
        .futureValue

      val estimate = client.getSavingsEstimate(merchantId).futureValue
      estimate.typicalAmount must be(defined)
      estimate.typicalAmount.get must be(BigDecimal("200"))
    }

    "return None when merchant has no expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val merchant = client.createMerchant(CreateMerchant.Request("New Shop", None, None, None)).futureValue

      val estimate = client.getSavingsEstimate(merchant.id).futureValue
      estimate.typicalAmount must be(None)
    }
  }

  "User isolation" should {
    "not return another user's savings goals" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createSavingsGoal(CreateSavingsGoal.Request("My Goal", None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      client2.getSavingsGoals.futureValue.data must be(empty)
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getSavingsGoals.expectError
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
