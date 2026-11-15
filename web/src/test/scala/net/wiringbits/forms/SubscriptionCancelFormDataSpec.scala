package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.CancelSubscription
import net.wiringbits.ui.forms.ExpLocalDate
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class SubscriptionCancelFormDataSpec extends AnyWordSpec {

  private def withTo(raw: String): SubscriptionCancelFormData = {
    val form = SubscriptionCancelFormData.initial
    form.copy(effectiveTo = form.effectiveTo.updated(ExpLocalDate.validate(raw)))
  }

  "fields" should {
    "list the end date" in {
      SubscriptionCancelFormData.initial.fields.map(_.name) must be(List("subscription-cancel-effective-to"))
    }
  }

  "submitRequest" should {
    "return a request with the end date" in {
      withTo("2026-07-01").submitRequest must be(
        Some(CancelSubscription.Request(effectiveTo = LocalDate.parse("2026-07-01")))
      )
    }

    "return None when the date is untouched" in {
      val form = SubscriptionCancelFormData.initial
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "return None when the date is not in YYYY-MM-DD format" in {
      val form = withTo("July 1st")
      form.isValid must be(false)
      form.formValidationErrors must not be empty
      form.submitRequest must be(None)
    }
  }
}
