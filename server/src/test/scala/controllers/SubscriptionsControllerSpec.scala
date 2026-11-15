package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.actions.subscriptions.*
import net.wiringbits.api.models.subscriptions.*
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.{Captcha, SubscriptionName}
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class SubscriptionsControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

  private val tokenGenerator = mock[TokenGenerator]
  private val emailApi = mock[EmailApi]
  when(emailApi.sendEmail(any[EmailRequest]())).thenReturn(Future.unit)
  private val captchaApi = mock[ReCaptchaApi]
  when(captchaApi.verify(any[Captcha]())).thenReturn(Future.successful(true))

  private val getAction = mock[GetSubscriptionsAction]
  private val createAction = mock[CreateSubscriptionAction]
  private val updateAction = mock[UpdateSubscriptionAction]
  private val deleteAction = mock[DeleteSubscriptionAction]

  override def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    super
      .guiceApplicationBuilder(container)
      .overrides(
        inject.bind[EmailApi].to(emailApi),
        inject.bind[ReCaptchaApi].to(captchaApi),
        inject.bind[TokenGenerator].to(tokenGenerator),
        inject.bind[GetSubscriptionsAction].to(getAction),
        inject.bind[CreateSubscriptionAction].to(createAction),
        inject.bind[UpdateSubscriptionAction].to(updateAction),
        inject.bind[DeleteSubscriptionAction].to(deleteAction)
      )

  private val unauthorizedError = "Unauthorized: Invalid or missing authentication"

  private def resetActions(): Unit = reset(getAction, createAction, updateAction, deleteAction)

  private val createRequest = CreateSubscription.Request(
    name = SubscriptionName.trusted("Streaming"),
    cadence = SubscriptionCadence.EveryNDays,
    intervalDays = Some(28),
    amountMin = Some(BigDecimal("9.99")),
    amountMax = Some(BigDecimal("12.50")),
    paymentMethod = SubscriptionPaymentMethod.CardPortal,
    categoryId = Some(UUID.randomUUID()),
    startDate = LocalDate.of(2026, 3, 15)
  )

  private val updateRequest = UpdateSubscription.Request(
    name = SubscriptionName.trusted("Gym"),
    cadence = SubscriptionCadence.Yearly,
    intervalDays = None,
    paymentMethod = SubscriptionPaymentMethod.DirectDebit,
    categoryId = None
  )

  "GET /subscriptions" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.getSubscriptions.expectError must be(unauthorizedError)
      verifyNoInteractions(getAction)
    }

    "return the action's subscriptions for the session user" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val expected = GetSubscriptions.Response(
        List(
          GetSubscriptions.Response.Subscription(
            id = UUID.randomUUID(),
            name = "Streaming",
            cadence = SubscriptionCadence.EveryNDays,
            intervalDays = Some(28),
            paymentMethod = SubscriptionPaymentMethod.CardPortal,
            categoryId = Some(UUID.randomUUID()),
            terms = List(
              SubscriptionTerm(
                id = UUID.randomUUID(),
                effectiveFrom = LocalDate.of(2025, 3, 15),
                effectiveTo = Some(LocalDate.of(2026, 3, 15)),
                amountMin = Some(BigDecimal("8.99")),
                amountMax = Some(BigDecimal("8.99"))
              ),
              SubscriptionTerm(
                id = UUID.randomUUID(),
                effectiveFrom = LocalDate.of(2026, 3, 15),
                effectiveTo = None,
                amountMin = Some(BigDecimal("9.99")),
                amountMax = Some(BigDecimal("12.50"))
              )
            ),
            createdAt = Instant.parse("2026-03-15T10:00:00Z")
          ),
          GetSubscriptions.Response.Subscription(
            id = UUID.randomUUID(),
            name = "Water",
            cadence = SubscriptionCadence.Monthly,
            intervalDays = None,
            paymentMethod = SubscriptionPaymentMethod.Cash,
            categoryId = None,
            terms = List(
              SubscriptionTerm(
                id = UUID.randomUUID(),
                effectiveFrom = LocalDate.of(2026, 2, 1),
                effectiveTo = None,
                amountMin = None,
                amountMax = None
              )
            ),
            createdAt = Instant.parse("2026-02-01T08:30:00Z")
          )
        )
      )
      when(getAction.apply(eqTo(userId))).thenReturn(Future.successful(expected))

      client.getSubscriptions.futureValue must be(expected)
      verify(getAction).apply(eqTo(userId))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      when(getAction.apply(eqTo(userId))).thenReturn(Future.failed(new RuntimeException("boom")))

      client.getSubscriptions.expectError must be("boom")
    }
  }

  "POST /subscriptions" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.createSubscription(createRequest).expectError must be(unauthorizedError)
      verifyNoInteractions(createAction)
    }

    "pass the session user and decoded request to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = UUID.randomUUID()
      when(createAction.apply(eqTo(userId), eqTo(createRequest)))
        .thenReturn(Future.successful(CreateSubscription.Response(id)))

      client.createSubscription(createRequest).futureValue must be(CreateSubscription.Response(id))
      verify(createAction).apply(eqTo(userId), eqTo(createRequest))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      when(createAction.apply(eqTo(userId), eqTo(createRequest)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.createSubscription(createRequest).expectError must be("boom")
    }
  }

  "PUT /subscriptions/:id" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.updateSubscription(UUID.randomUUID(), updateRequest).expectError must be(unauthorizedError)
      verifyNoInteractions(updateAction)
    }

    "pass the path id, session user and decoded request to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(updateAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(updateRequest)))
        .thenReturn(Future.successful(UpdateSubscription.Response()))

      client.updateSubscription(subscriptionId, updateRequest).futureValue must be(UpdateSubscription.Response())
      verify(updateAction).apply(eqTo(subscriptionId), eqTo(userId), eqTo(updateRequest))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(updateAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(updateRequest)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.updateSubscription(subscriptionId, updateRequest).expectError must be("boom")
    }
  }

  "DELETE /subscriptions/:id" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.deleteSubscription(UUID.randomUUID()).expectError must be(unauthorizedError)
      verifyNoInteractions(deleteAction)
    }

    "pass the path id and session user to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(deleteAction.apply(eqTo(subscriptionId), eqTo(userId)))
        .thenReturn(Future.successful(DeleteSubscription.Response()))

      client.deleteSubscription(subscriptionId).futureValue must be(DeleteSubscription.Response())
      verify(deleteAction).apply(eqTo(subscriptionId), eqTo(userId))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(deleteAction.apply(eqTo(subscriptionId), eqTo(userId)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.deleteSubscription(subscriptionId).expectError must be("boom")
    }
  }
}
