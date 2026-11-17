package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.AddSubscriptionManualPayment
import net.wiringbits.common.models.SubscriptionManualPaymentNote
import net.wiringbits.ui.forms.{ExpLocalDate, SubscriptionAmount, SubscriptionManualPaymentNoteValidator}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class SubscriptionManualPaymentFormDataSpec extends AnyWordSpec {

  extension (form: SubscriptionManualPaymentFormData) {
    private def withPaidDate(raw: String): SubscriptionManualPaymentFormData =
      form.copy(paidDate = form.paidDate.updated(ExpLocalDate.validate(raw)))
    private def withAmount(raw: String): SubscriptionManualPaymentFormData =
      form.copy(amount = form.amount.updated(SubscriptionAmount.validate(raw)))
    private def withNote(raw: String): SubscriptionManualPaymentFormData =
      form.copy(note = form.note.updated(SubscriptionManualPaymentNoteValidator.optionalValidator(raw)))
  }

  private val validForm = SubscriptionManualPaymentFormData.initial
    .withPaidDate("2025-03-01")
    .withAmount("12")
    .withNote(" Paid in cash ")

  private def assertBlocked(form: SubscriptionManualPaymentFormData) = {
    form.isValid must be(false)
    form.submitRequest must be(None)
  }

  "fields" should {
    "list the paid date, amount and note inputs under the manual-payment prefix" in {
      validForm.fields.map(_.name) must be(
        List(
          "subscription-manual-payment-paid-date",
          "subscription-manual-payment-amount",
          "subscription-manual-payment-note"
        )
      )
    }

    "mark the paid date and amount as required and the note as optional" in {
      validForm.fields.map(_.required) must be(List(true, true, false))
    }
  }

  "submitRequest" should {
    "return a request with the paid date, amount and trimmed note" in {
      validForm.submitRequest must be(
        Some(
          AddSubscriptionManualPayment.Request(
            paidDate = LocalDate.parse("2025-03-01"),
            amount = BigDecimal("12.00"),
            note = Some(SubscriptionManualPaymentNote.trusted("Paid in cash"))
          )
        )
      )
    }

    "return a request without a note when the note is blank" in {
      validForm.withNote("   ").submitRequest.map(_.note) must be(Some(None))
    }

    "return a request without a note when the note was never touched" in {
      validForm.copy(note = SubscriptionManualPaymentFormData.initial.note).submitRequest.map(_.note) must be(
        Some(None)
      )
    }

    "return None for the untouched initial form" in {
      assertBlocked(SubscriptionManualPaymentFormData.initial)
    }

    "return None when the paid date is missing" in {
      assertBlocked(validForm.copy(paidDate = SubscriptionManualPaymentFormData.initial.paidDate))
    }

    "return None when the amount is missing" in {
      assertBlocked(validForm.copy(amount = SubscriptionManualPaymentFormData.initial.amount))
    }

    "return None when the paid date is not in YYYY-MM-DD format" in {
      assertBlocked(validForm.withPaidDate("01/03/2025"))
    }

    "return None when the amount is zero" in {
      assertBlocked(validForm.withAmount("0"))
    }

    "return None when the amount is negative" in {
      assertBlocked(validForm.withAmount("-5"))
    }

    "return None when the amount has more than two decimals" in {
      assertBlocked(validForm.withAmount("1.234"))
    }

    "return None when the note exceeds the maximum length" in {
      assertBlocked(validForm.withNote("a" * (SubscriptionManualPaymentNote.MaxLength + 1)))
    }
  }
}
