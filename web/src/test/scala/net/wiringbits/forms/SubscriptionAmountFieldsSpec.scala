package net.wiringbits.forms

import net.wiringbits.ui.forms.SubscriptionAmount
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionAmountFieldsSpec extends AnyWordSpec {

  private val initial = SubscriptionAmountFields.initial("amounts")

  private def exact(raw: String): SubscriptionAmountFields =
    initial.copy(exact = initial.exact.updated(SubscriptionAmount.optionalValidator(raw)))

  private def range(min: String, max: String): SubscriptionAmountFields =
    initial.copy(
      mode = AmountMode.Range,
      min = initial.min.updated(SubscriptionAmount.optionalValidator(min)),
      max = initial.max.updated(SubscriptionAmount.optionalValidator(max))
    )

  "fields" should {
    "list only the exact amount in exact mode" in {
      initial.fields.map(_.name) must be(List("amounts-exact-amount"))
    }

    "list both bounds in range mode" in {
      range("", "").fields.map(_.name) must be(List("amounts-min-amount", "amounts-max-amount"))
    }
  }

  "resolved" should {
    "send min equal to max for an exact amount" in {
      exact("12.50").resolved must be(Some((Some(BigDecimal("12.50")), Some(BigDecimal("12.50")))))
    }

    "send both bounds for a range" in {
      range("10", "25.00").resolved must be(Some((Some(BigDecimal("10")), Some(BigDecimal("25.00")))))
    }

    "send no amounts when the exact amount is blank" in {
      exact(" ").resolved must be(Some((None, None)))
    }

    "send no amounts when both bounds are blank" in {
      range("", "").resolved must be(Some((None, None)))
    }

    "ignore the hidden range bounds in exact mode" in {
      exact("12.50").copy(min = range("abc", "").min).resolved must be(
        Some((Some(BigDecimal("12.50")), Some(BigDecimal("12.50"))))
      )
    }

    "be None when the amount is not a number" in {
      exact("abc").resolved must be(None)
      range("10", "lots").resolved must be(None)
    }
  }

  "rangeError" should {
    "be None for a valid range" in {
      range("10", "25").rangeError must be(None)
    }

    "report a minimum greater than the maximum" in {
      range("30", "20").rangeError must be(Some("Minimum amount must not be greater than the maximum amount"))
    }

    "report a range with only one bound set" in {
      range("10", "").rangeError must be(Some("Minimum and maximum amounts must be both set or both empty"))
    }
  }
}
