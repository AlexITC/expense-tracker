package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.ResumeSubscription
import net.wiringbits.ui.forms.{ExpLocalDate, SubscriptionAmount}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class SubscriptionResumeFormDataSpec extends AnyWordSpec {

  extension (form: SubscriptionResumeFormData) {
    private def withFrom(raw: String): SubscriptionResumeFormData =
      form.copy(effectiveFrom = form.effectiveFrom.updated(ExpLocalDate.validate(raw)))
    private def withExact(raw: String): SubscriptionResumeFormData =
      form.copy(amounts =
        form.amounts.copy(exact = form.amounts.exact.updated(SubscriptionAmount.optionalValidator(raw)))
      )
  }

  private val validForm = SubscriptionResumeFormData.initial.withFrom("2026-06-01").withExact("15.00")

  private def assertBlocked(form: SubscriptionResumeFormData) = {
    form.isValid must be(false)
    form.formValidationErrors must not be empty
    form.submitRequest must be(None)
  }

  "fields" should {
    "list the date and the amount inputs under the resume prefix" in {
      validForm.fields.map(_.name) must be(
        List("subscription-resume-effective-from", "subscription-resume-exact-amount")
      )
    }
  }

  "submitRequest" should {
    "return a request with the date and amounts" in {
      validForm.submitRequest must be(
        Some(
          ResumeSubscription.Request(
            effectiveFrom = LocalDate.parse("2026-06-01"),
            amountMin = Some(BigDecimal("15.00")),
            amountMax = Some(BigDecimal("15.00"))
          )
        )
      )
    }

    "return None when the date is untouched" in {
      assertBlocked(validForm.copy(effectiveFrom = SubscriptionResumeFormData.initial.effectiveFrom))
    }

    "return None when the date is not in YYYY-MM-DD format" in {
      assertBlocked(validForm.withFrom("01/06/2026"))
    }

    "return None when the amount is invalid" in {
      assertBlocked(validForm.withExact("abc"))
    }
  }
}
