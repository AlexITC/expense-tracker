package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.SubscriptionCadence
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SubscriptionValidatorSpec extends AnyWordSpec with Matchers {

  private def validateAmounts(min: Option[BigDecimal], max: Option[BigDecimal]) =
    SubscriptionValidator.validate(
      SubscriptionCadence.Monthly,
      intervalDays = None,
      intervalMonths = None,
      amountMin = min,
      amountMax = max
    )

  "validate" should {
    "accept a monthly or yearly cadence without interval and amounts" in {
      List(SubscriptionCadence.Monthly, SubscriptionCadence.Yearly).foreach { cadence =>
        SubscriptionValidator.validate(
          cadence,
          intervalDays = None,
          intervalMonths = None,
          amountMin = None,
          amountMax = None
        ) mustBe Right(
          ()
        )
      }
    }

    "accept an every-N-days cadence with a positive interval" in {
      SubscriptionValidator.validate(
        SubscriptionCadence.EveryNDays,
        intervalDays = Some(14),
        intervalMonths = None,
        amountMin = None,
        amountMax = None
      ) mustBe Right(())
    }

    "reject an every-N-days cadence without interval" in {
      SubscriptionValidator.validate(
        SubscriptionCadence.EveryNDays,
        intervalDays = None,
        intervalMonths = None,
        amountMin = None,
        amountMax = None
      ) mustBe
        Left("Interval days is required for an every-N-days cadence")
    }

    "reject a zero or negative interval" in {
      List(0, -3).foreach { days =>
        SubscriptionValidator.validate(
          SubscriptionCadence.EveryNDays,
          intervalDays = Some(days),
          intervalMonths = None,
          amountMin = None,
          amountMax = None
        ) mustBe
          Left("Interval days must be greater than zero")
      }
    }

    "reject an interval on a monthly or yearly cadence" in {
      List(SubscriptionCadence.Monthly, SubscriptionCadence.Yearly).foreach { cadence =>
        SubscriptionValidator.validate(
          cadence,
          intervalDays = Some(30),
          intervalMonths = None,
          amountMin = None,
          amountMax = None
        ) mustBe
          Left("Interval days is only allowed for an every-N-days cadence")
      }
    }

    "accept an every-N-months cadence within the interval bounds" in {
      List(1, 3, 24).foreach { months =>
        SubscriptionValidator.validate(
          SubscriptionCadence.EveryNMonths,
          intervalDays = None,
          intervalMonths = Some(months),
          amountMin = None,
          amountMax = None
        ) mustBe Right(())
      }
    }

    "reject an every-N-months cadence without interval" in {
      SubscriptionValidator.validate(
        SubscriptionCadence.EveryNMonths,
        intervalDays = None,
        intervalMonths = None,
        amountMin = None,
        amountMax = None
      ) mustBe
        Left("Interval months is required for an every-N-months cadence")
    }

    "reject an interval months outside the bounds" in {
      List(0, -1, 25).foreach { months =>
        SubscriptionValidator.validate(
          SubscriptionCadence.EveryNMonths,
          intervalDays = None,
          intervalMonths = Some(months),
          amountMin = None,
          amountMax = None
        ) mustBe
          Left("Interval months must be between 1 and 24")
      }
    }

    "reject interval months on any other cadence" in {
      List(SubscriptionCadence.Monthly, SubscriptionCadence.Yearly).foreach { cadence =>
        SubscriptionValidator.validate(
          cadence,
          intervalDays = None,
          intervalMonths = Some(3),
          amountMin = None,
          amountMax = None
        ) mustBe
          Left("Interval months is only allowed for an every-N-months cadence")
      }
      SubscriptionValidator.validate(
        SubscriptionCadence.EveryNDays,
        intervalDays = Some(14),
        intervalMonths = Some(3),
        amountMin = None,
        amountMax = None
      ) mustBe
        Left("Interval months is only allowed for an every-N-months cadence")
    }

    "reject interval days on an every-N-months cadence" in {
      SubscriptionValidator.validate(
        SubscriptionCadence.EveryNMonths,
        intervalDays = Some(30),
        intervalMonths = Some(3),
        amountMin = None,
        amountMax = None
      ) mustBe
        Left("Interval days is only allowed for an every-N-days cadence")
    }

    "accept a valid amount range" in {
      validateAmounts(min = Some(BigDecimal("9.99")), max = Some(BigDecimal("12.50"))) mustBe Right(())
    }

    "accept equal minimum and maximum amounts" in {
      validateAmounts(min = Some(BigDecimal("10")), max = Some(BigDecimal("10.00"))) mustBe Right(())
    }

    "reject only one amount bound" in {
      val expected = Left("Minimum and maximum amounts must be both set or both empty")
      validateAmounts(min = Some(BigDecimal("5")), max = None) mustBe expected
      validateAmounts(min = None, max = Some(BigDecimal("5"))) mustBe expected
    }

    "reject a zero or negative amount" in {
      validateAmounts(min = Some(BigDecimal("0")), max = Some(BigDecimal("5"))) mustBe Left(
        "Minimum amount must be greater than zero"
      )
      validateAmounts(min = Some(BigDecimal("1")), max = Some(BigDecimal("-5"))) mustBe Left(
        "Maximum amount must be greater than zero"
      )
    }

    "reject an amount with more than two decimal places" in {
      validateAmounts(min = Some(BigDecimal("1.001")), max = Some(BigDecimal("5"))) mustBe
        Left("Minimum amount must have at most 2 decimal places")
      validateAmounts(min = Some(BigDecimal("1")), max = Some(BigDecimal("5.123"))) mustBe
        Left("Maximum amount must have at most 2 decimal places")
    }

    "accept an amount with exactly ten integer digits" in {
      validateAmounts(min = Some(BigDecimal("1")), max = Some(BigDecimal("9999999999.99"))) mustBe Right(())
    }

    "reject an amount with more than ten integer digits" in {
      validateAmounts(min = Some(BigDecimal("10000000000")), max = Some(BigDecimal("10000000000"))) mustBe
        Left("Minimum amount must have at most 10 integer digits")
      validateAmounts(min = Some(BigDecimal("1")), max = Some(BigDecimal("10000000000"))) mustBe
        Left("Maximum amount must have at most 10 integer digits")
    }

    "reject a minimum greater than the maximum" in {
      validateAmounts(min = Some(BigDecimal("20")), max = Some(BigDecimal("10"))) mustBe
        Left("Minimum amount must not be greater than the maximum amount")
    }

    "report the interval error before the amount error" in {
      SubscriptionValidator.validate(
        SubscriptionCadence.EveryNDays,
        intervalDays = None,
        intervalMonths = None,
        amountMin = Some(BigDecimal("5")),
        amountMax = None
      ) mustBe
        Left("Interval days is required for an every-N-days cadence")
    }
  }

  "validateExtraChargeAmount" should {
    "accept a positive amount with up to two decimals and ten integer digits" in {
      List(BigDecimal("0.01"), BigDecimal("12"), BigDecimal("9999999999.99")).foreach { amount =>
        SubscriptionValidator.validateExtraChargeAmount(amount) mustBe Right(())
      }
    }

    "reject a zero or negative amount" in {
      List(BigDecimal("0"), BigDecimal("-5.00")).foreach { amount =>
        SubscriptionValidator.validateExtraChargeAmount(amount) mustBe Left("Amount must be greater than zero")
      }
    }

    "reject more than two decimals" in {
      SubscriptionValidator.validateExtraChargeAmount(BigDecimal("1.234")) mustBe
        Left("Amount must have at most 2 decimal places")
    }

    "reject more than ten integer digits" in {
      SubscriptionValidator.validateExtraChargeAmount(BigDecimal("12345678901")) mustBe
        Left("Amount must have at most 10 integer digits")
    }
  }

  "validateManualPaymentAmount" should {
    "accept a positive amount with up to two decimals and ten integer digits" in {
      List(BigDecimal("0.01"), BigDecimal("12"), BigDecimal("9999999999.99")).foreach { amount =>
        SubscriptionValidator.validateManualPaymentAmount(amount) mustBe Right(())
      }
    }

    "reject a zero or negative amount" in {
      List(BigDecimal("0"), BigDecimal("-5.00")).foreach { amount =>
        SubscriptionValidator.validateManualPaymentAmount(amount) mustBe Left("Amount must be greater than zero")
      }
    }

    "reject more than two decimals" in {
      SubscriptionValidator.validateManualPaymentAmount(BigDecimal("1.234")) mustBe
        Left("Amount must have at most 2 decimal places")
    }

    "reject more than ten integer digits" in {
      SubscriptionValidator.validateManualPaymentAmount(BigDecimal("12345678901")) mustBe
        Left("Amount must have at most 10 integer digits")
    }
  }
}
