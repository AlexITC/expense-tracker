package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.{AddSubscriptionExtraCharge, SubscriptionExtraChargeRecurrence}
import net.wiringbits.common.models.SubscriptionExtraChargeLabel
import net.wiringbits.ui.forms.{ExpLocalDate, SubscriptionAmount}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class SubscriptionExtraChargeFormDataSpec extends AnyWordSpec {

  extension (form: SubscriptionExtraChargeFormData) {
    private def withLabel(raw: String): SubscriptionExtraChargeFormData =
      form.copy(label = form.label.updated(SubscriptionExtraChargeLabel.validate(raw)))
    private def withAmount(raw: String): SubscriptionExtraChargeFormData =
      form.copy(amount = form.amount.updated(SubscriptionAmount.validate(raw)))
    private def withDueDate(raw: String): SubscriptionExtraChargeFormData =
      form.copy(dueDate = form.dueDate.updated(ExpLocalDate.validate(raw)))
  }

  private val validForm = SubscriptionExtraChargeFormData.initial
    .withLabel(" Membership fee ")
    .withAmount("12")
    .withDueDate("2025-03-01")
    .copy(recurrence = SubscriptionExtraChargeRecurrence.Yearly)

  private def assertBlocked(form: SubscriptionExtraChargeFormData) = {
    form.isValid must be(false)
    form.submitRequest must be(None)
  }

  "fields" should {
    "list the label, amount and due date inputs under the extra-charge prefix" in {
      validForm.fields.map(_.name) must be(
        List(
          "subscription-extra-charge-label",
          "subscription-extra-charge-amount",
          "subscription-extra-charge-due-date"
        )
      )
    }

    "mark every input as required" in {
      validForm.fields.map(_.required) must be(List(true, true, true))
    }
  }

  "submitRequest" should {
    "return a request with the trimmed label, amount, recurrence and due date" in {
      validForm.submitRequest must be(
        Some(
          AddSubscriptionExtraCharge.Request(
            label = SubscriptionExtraChargeLabel.trusted("Membership fee"),
            amount = BigDecimal("12.00"),
            recurrence = SubscriptionExtraChargeRecurrence.Yearly,
            dueDate = LocalDate.parse("2025-03-01")
          )
        )
      )
    }

    "return None for the untouched initial form" in {
      assertBlocked(SubscriptionExtraChargeFormData.initial)
    }

    "return None when the label is blank" in {
      assertBlocked(validForm.withLabel("   "))
    }

    "return None when the amount is not positive" in {
      assertBlocked(validForm.withAmount("0"))
    }

    "return None when the amount has more than two decimals" in {
      assertBlocked(validForm.withAmount("1.234"))
    }

    "return None when the due date is not in YYYY-MM-DD format" in {
      assertBlocked(validForm.withDueDate("01/03/2025"))
    }
  }
}
