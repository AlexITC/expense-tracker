package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.actions.subscriptions.*
import net.wiringbits.api.models.subscriptions.*
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.{
  Captcha,
  SubscriptionDescription,
  SubscriptionExtraChargeLabel,
  SubscriptionManualPaymentNote,
  SubscriptionName
}
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
  private val changePriceAction = mock[ChangeSubscriptionPriceAction]
  private val cancelAction = mock[CancelSubscriptionAction]
  private val resumeAction = mock[ResumeSubscriptionAction]
  private val startDateSuggestionAction = mock[GetSubscriptionStartDateSuggestionAction]
  private val addExtraChargeAction = mock[AddSubscriptionExtraChargeAction]
  private val deleteExtraChargeAction = mock[DeleteSubscriptionExtraChargeAction]
  private val reconciliationAction = mock[GetSubscriptionReconciliationAction]
  private val addManualPaymentAction = mock[AddSubscriptionManualPaymentAction]
  private val deleteManualPaymentAction = mock[DeleteSubscriptionManualPaymentAction]

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
        inject.bind[DeleteSubscriptionAction].to(deleteAction),
        inject.bind[ChangeSubscriptionPriceAction].to(changePriceAction),
        inject.bind[CancelSubscriptionAction].to(cancelAction),
        inject.bind[ResumeSubscriptionAction].to(resumeAction),
        inject.bind[GetSubscriptionStartDateSuggestionAction].to(startDateSuggestionAction),
        inject.bind[AddSubscriptionExtraChargeAction].to(addExtraChargeAction),
        inject.bind[DeleteSubscriptionExtraChargeAction].to(deleteExtraChargeAction),
        inject.bind[GetSubscriptionReconciliationAction].to(reconciliationAction),
        inject.bind[AddSubscriptionManualPaymentAction].to(addManualPaymentAction),
        inject.bind[DeleteSubscriptionManualPaymentAction].to(deleteManualPaymentAction)
      )

  private val unauthorizedError = "Unauthorized: Invalid or missing authentication"

  private def resetActions(): Unit =
    reset(
      getAction,
      createAction,
      updateAction,
      deleteAction,
      changePriceAction,
      cancelAction,
      resumeAction,
      startDateSuggestionAction,
      addExtraChargeAction,
      deleteExtraChargeAction,
      reconciliationAction,
      addManualPaymentAction,
      deleteManualPaymentAction
    )

  private val createRequest = CreateSubscription.Request(
    name = SubscriptionName.trusted("Streaming"),
    cadence = SubscriptionCadence.EveryNDays,
    intervalDays = Some(28),
    intervalMonths = None,
    amountMin = Some(BigDecimal("9.99")),
    amountMax = Some(BigDecimal("12.50")),
    paymentMethod = SubscriptionPaymentMethod.CardPortal,
    categoryId = Some(UUID.randomUUID()),
    merchantId = Some(UUID.randomUUID()),
    description = Some(SubscriptionDescription.trusted("Family plan")),
    startDate = LocalDate.of(2026, 3, 15)
  )

  private val updateRequest = UpdateSubscription.Request(
    name = SubscriptionName.trusted("Gym"),
    cadence = SubscriptionCadence.Yearly,
    intervalDays = None,
    intervalMonths = None,
    paymentMethod = SubscriptionPaymentMethod.DirectDebit,
    categoryId = None,
    merchantId = Some(UUID.randomUUID()),
    description = None
  )

  private val changePriceRequest = ChangeSubscriptionPrice.Request(
    effectiveFrom = LocalDate.of(2026, 4, 1),
    amountMin = Some(BigDecimal("10.99")),
    amountMax = Some(BigDecimal("13.50"))
  )

  private val cancelRequest = CancelSubscription.Request(effectiveTo = LocalDate.of(2026, 5, 1))

  private val resumeRequest = ResumeSubscription.Request(
    effectiveFrom = LocalDate.of(2026, 6, 1),
    amountMin = None,
    amountMax = None
  )

  private val addExtraChargeRequest = AddSubscriptionExtraCharge.Request(
    label = SubscriptionExtraChargeLabel.trusted("Yearly fee"),
    amount = BigDecimal("12.00"),
    recurrence = SubscriptionExtraChargeRecurrence.Yearly,
    dueDate = LocalDate.of(2025, 3, 1)
  )

  private val addManualPaymentRequest = AddSubscriptionManualPayment.Request(
    paidDate = LocalDate.of(2026, 3, 2),
    amount = BigDecimal("15.00"),
    note = Some(SubscriptionManualPaymentNote.trusted("Paid at the front desk"))
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
            intervalMonths = None,
            paymentMethod = SubscriptionPaymentMethod.CardPortal,
            categoryId = Some(UUID.randomUUID()),
            merchantId = Some(UUID.randomUUID()),
            description = Some("Family plan"),
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
            extraCharges = List.empty,
            createdAt = Instant.parse("2026-03-15T10:00:00Z")
          ),
          GetSubscriptions.Response.Subscription(
            id = UUID.randomUUID(),
            name = "Water",
            cadence = SubscriptionCadence.Monthly,
            intervalDays = None,
            intervalMonths = None,
            paymentMethod = SubscriptionPaymentMethod.Cash,
            categoryId = None,
            merchantId = None,
            description = None,
            terms = List(
              SubscriptionTerm(
                id = UUID.randomUUID(),
                effectiveFrom = LocalDate.of(2026, 2, 1),
                effectiveTo = None,
                amountMin = None,
                amountMax = None
              )
            ),
            extraCharges = List.empty,
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

  "POST /subscriptions/:id/price-change" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.changeSubscriptionPrice(UUID.randomUUID(), changePriceRequest).expectError must be(unauthorizedError)
      verifyNoInteractions(changePriceAction)
    }

    "pass the path id, session user and decoded request to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(changePriceAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(changePriceRequest)))
        .thenReturn(Future.successful(ChangeSubscriptionPrice.Response()))

      client.changeSubscriptionPrice(subscriptionId, changePriceRequest).futureValue must be(
        ChangeSubscriptionPrice.Response()
      )
      verify(changePriceAction).apply(eqTo(subscriptionId), eqTo(userId), eqTo(changePriceRequest))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(changePriceAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(changePriceRequest)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.changeSubscriptionPrice(subscriptionId, changePriceRequest).expectError must be("boom")
    }
  }

  "POST /subscriptions/:id/cancel" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.cancelSubscription(UUID.randomUUID(), cancelRequest).expectError must be(unauthorizedError)
      verifyNoInteractions(cancelAction)
    }

    "pass the path id, session user and decoded request to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(cancelAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(cancelRequest)))
        .thenReturn(Future.successful(CancelSubscription.Response()))

      client.cancelSubscription(subscriptionId, cancelRequest).futureValue must be(CancelSubscription.Response())
      verify(cancelAction).apply(eqTo(subscriptionId), eqTo(userId), eqTo(cancelRequest))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(cancelAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(cancelRequest)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.cancelSubscription(subscriptionId, cancelRequest).expectError must be("boom")
    }
  }

  "POST /subscriptions/:id/resume" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.resumeSubscription(UUID.randomUUID(), resumeRequest).expectError must be(unauthorizedError)
      verifyNoInteractions(resumeAction)
    }

    "pass the path id, session user and decoded request to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(resumeAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(resumeRequest)))
        .thenReturn(Future.successful(ResumeSubscription.Response()))

      client.resumeSubscription(subscriptionId, resumeRequest).futureValue must be(ResumeSubscription.Response())
      verify(resumeAction).apply(eqTo(subscriptionId), eqTo(userId), eqTo(resumeRequest))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(resumeAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(resumeRequest)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.resumeSubscription(subscriptionId, resumeRequest).expectError must be("boom")
    }
  }

  "GET /subscriptions/start-date-suggestion/:merchantId" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.getSubscriptionStartDateSuggestion(UUID.randomUUID()).expectError must be(unauthorizedError)
      verifyNoInteractions(startDateSuggestionAction)
    }

    "pass the session user and path merchant id to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val merchantId = UUID.randomUUID()
      val expected = GetSubscriptionStartDateSuggestion.Response(Some(LocalDate.of(2025, 2, 3)))
      when(startDateSuggestionAction.apply(eqTo(userId), eqTo(merchantId)))
        .thenReturn(Future.successful(expected))

      client.getSubscriptionStartDateSuggestion(merchantId).futureValue must be(expected)
      verify(startDateSuggestionAction).apply(eqTo(userId), eqTo(merchantId))
    }

    "encode a missing suggestion as None" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val merchantId = UUID.randomUUID()
      val expected = GetSubscriptionStartDateSuggestion.Response(None)
      when(startDateSuggestionAction.apply(eqTo(userId), eqTo(merchantId)))
        .thenReturn(Future.successful(expected))

      client.getSubscriptionStartDateSuggestion(merchantId).futureValue must be(expected)
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val merchantId = UUID.randomUUID()
      when(startDateSuggestionAction.apply(eqTo(userId), eqTo(merchantId)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.getSubscriptionStartDateSuggestion(merchantId).expectError must be("boom")
    }
  }

  "POST /subscriptions/:id/extra-charges" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.addSubscriptionExtraCharge(UUID.randomUUID(), addExtraChargeRequest).expectError must be(
        unauthorizedError
      )
      verifyNoInteractions(addExtraChargeAction)
    }

    "pass the path id, session user and decoded request to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      val expected = AddSubscriptionExtraCharge.Response(UUID.randomUUID())
      when(addExtraChargeAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(addExtraChargeRequest)))
        .thenReturn(Future.successful(expected))

      client.addSubscriptionExtraCharge(subscriptionId, addExtraChargeRequest).futureValue must be(expected)
      verify(addExtraChargeAction).apply(eqTo(subscriptionId), eqTo(userId), eqTo(addExtraChargeRequest))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(addExtraChargeAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(addExtraChargeRequest)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.addSubscriptionExtraCharge(subscriptionId, addExtraChargeRequest).expectError must be("boom")
    }
  }

  "DELETE /subscriptions/:id/extra-charges/:extraChargeId" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.deleteSubscriptionExtraCharge(UUID.randomUUID(), UUID.randomUUID()).expectError must be(
        unauthorizedError
      )
      verifyNoInteractions(deleteExtraChargeAction)
    }

    "pass both path ids and the session user to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      val extraChargeId = UUID.randomUUID()
      when(deleteExtraChargeAction.apply(eqTo(subscriptionId), eqTo(extraChargeId), eqTo(userId)))
        .thenReturn(Future.successful(DeleteSubscriptionExtraCharge.Response()))

      client.deleteSubscriptionExtraCharge(subscriptionId, extraChargeId).futureValue must be(
        DeleteSubscriptionExtraCharge.Response()
      )
      verify(deleteExtraChargeAction).apply(eqTo(subscriptionId), eqTo(extraChargeId), eqTo(userId))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      val extraChargeId = UUID.randomUUID()
      when(deleteExtraChargeAction.apply(eqTo(subscriptionId), eqTo(extraChargeId), eqTo(userId)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.deleteSubscriptionExtraCharge(subscriptionId, extraChargeId).expectError must be("boom")
    }
  }

  "GET /subscriptions/reconciliation" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.getSubscriptionReconciliation(None).expectError must be(unauthorizedError)
      verifyNoInteractions(reconciliationAction)
    }

    "pass the session user without a subscription filter and encode every cycle status" in withApiClient {
      implicit client =>
        resetActions()
        val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
        val termId = UUID.randomUUID()
        def cycle(dueDate: LocalDate, status: SubscriptionCycleStatus) = SubscriptionCycle(
          dueDate = dueDate,
          termId = termId,
          amountMin = Some(BigDecimal("9.99")),
          amountMax = Some(BigDecimal("12.50")),
          status = status
        )
        val manualPayment = SubscriptionManualPayment(
          id = UUID.randomUUID(),
          paidDate = LocalDate.of(2026, 3, 2),
          amount = BigDecimal("15.00"),
          note = Some(SubscriptionManualPaymentNote.trusted("Paid at the front desk"))
        )
        val expected = GetSubscriptionReconciliation.Response(
          List(
            GetSubscriptionReconciliation.Response.Subscription(
              subscriptionId = UUID.randomUUID(),
              cycles = List(
                cycle(
                  LocalDate.of(2026, 1, 1),
                  SubscriptionCycleStatus.Matched(
                    charge = SubscriptionCharge(
                      source = SubscriptionChargeSource.Expense,
                      id = UUID.randomUUID(),
                      date = LocalDate.of(2026, 1, 3),
                      amount = BigDecimal("10.99")
                    ),
                    late = true
                  )
                ),
                cycle(
                  LocalDate.of(2026, 2, 1),
                  SubscriptionCycleStatus.Retried(
                    List(
                      SubscriptionCharge(
                        source = SubscriptionChargeSource.BankStatementRow,
                        id = UUID.randomUUID(),
                        date = LocalDate.of(2026, 2, 1),
                        amount = BigDecimal("10.99")
                      ),
                      SubscriptionCharge(
                        source = SubscriptionChargeSource.Expense,
                        id = UUID.randomUUID(),
                        date = LocalDate.of(2026, 2, 3),
                        amount = BigDecimal("10.99")
                      )
                    )
                  )
                ),
                cycle(
                  LocalDate.of(2026, 3, 1),
                  SubscriptionCycleStatus.Matched(
                    charge = SubscriptionCharge(
                      source = SubscriptionChargeSource.ManualPayment,
                      id = manualPayment.id,
                      date = manualPayment.paidDate,
                      amount = manualPayment.amount
                    ),
                    late = false
                  )
                ),
                cycle(LocalDate.of(2026, 4, 1), SubscriptionCycleStatus.Missing),
                cycle(LocalDate.of(2026, 5, 1), SubscriptionCycleStatus.Upcoming)
              ),
              manualPayments = List(manualPayment)
            )
          )
        )
        when(reconciliationAction.apply(eqTo(userId), eqTo(None))).thenReturn(Future.successful(expected))

        client.getSubscriptionReconciliation(None).futureValue must be(expected)
        verify(reconciliationAction).apply(eqTo(userId), eqTo(None))
    }

    "pass the query subscription id to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      val expected = GetSubscriptionReconciliation.Response(
        List(GetSubscriptionReconciliation.Response.Subscription(subscriptionId, List.empty, List.empty))
      )
      when(reconciliationAction.apply(eqTo(userId), eqTo(Some(subscriptionId))))
        .thenReturn(Future.successful(expected))

      client.getSubscriptionReconciliation(Some(subscriptionId)).futureValue must be(expected)
      verify(reconciliationAction).apply(eqTo(userId), eqTo(Some(subscriptionId)))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(reconciliationAction.apply(eqTo(userId), eqTo(Some(subscriptionId))))
        .thenReturn(Future.failed(new RuntimeException("Subscription not found")))

      client.getSubscriptionReconciliation(Some(subscriptionId)).expectError must be("Subscription not found")
    }
  }

  "POST /subscriptions/:id/manual-payments" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.addSubscriptionManualPayment(UUID.randomUUID(), addManualPaymentRequest).expectError must be(
        unauthorizedError
      )
      verifyNoInteractions(addManualPaymentAction)
    }

    "pass the path id, session user and decoded request to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      val expected = AddSubscriptionManualPayment.Response(UUID.randomUUID())
      when(addManualPaymentAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(addManualPaymentRequest)))
        .thenReturn(Future.successful(expected))

      client.addSubscriptionManualPayment(subscriptionId, addManualPaymentRequest).futureValue must be(expected)
      verify(addManualPaymentAction).apply(eqTo(subscriptionId), eqTo(userId), eqTo(addManualPaymentRequest))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      when(addManualPaymentAction.apply(eqTo(subscriptionId), eqTo(userId), eqTo(addManualPaymentRequest)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.addSubscriptionManualPayment(subscriptionId, addManualPaymentRequest).expectError must be("boom")
    }
  }

  "DELETE /subscriptions/:id/manual-payments/:manualPaymentId" should {
    "reject unauthenticated requests without invoking the action" in withApiClient { client =>
      resetActions()

      client.deleteSubscriptionManualPayment(UUID.randomUUID(), UUID.randomUUID()).expectError must be(
        unauthorizedError
      )
      verifyNoInteractions(deleteManualPaymentAction)
    }

    "pass both path ids and the session user to the action" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      val manualPaymentId = UUID.randomUUID()
      when(deleteManualPaymentAction.apply(eqTo(subscriptionId), eqTo(manualPaymentId), eqTo(userId)))
        .thenReturn(Future.successful(DeleteSubscriptionManualPayment.Response()))

      client.deleteSubscriptionManualPayment(subscriptionId, manualPaymentId).futureValue must be(
        DeleteSubscriptionManualPayment.Response()
      )
      verify(deleteManualPaymentAction).apply(eqTo(subscriptionId), eqTo(manualPaymentId), eqTo(userId))
    }

    "encode an action failure as the error message" in withApiClient { implicit client =>
      resetActions()
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val subscriptionId = UUID.randomUUID()
      val manualPaymentId = UUID.randomUUID()
      when(deleteManualPaymentAction.apply(eqTo(subscriptionId), eqTo(manualPaymentId), eqTo(userId)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      client.deleteSubscriptionManualPayment(subscriptionId, manualPaymentId).expectError must be("boom")
    }
  }
}
