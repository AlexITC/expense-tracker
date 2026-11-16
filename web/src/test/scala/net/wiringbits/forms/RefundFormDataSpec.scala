package net.wiringbits.forms

import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.refunds.GetRefunds
import net.wiringbits.ui.forms.{ExpLocalDate, ExpNotes, ExpOptionalId, PositiveBigDecimal}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class RefundFormDataSpec extends AnyWordSpec {

  private val foodId = UUID.randomUUID()
  private val store =
    GetMerchants.Response.Merchant(UUID.randomUUID(), "Store", None, Some(foodId), None, reviewed = true, Instant.EPOCH)
  private val shop =
    GetMerchants.Response.Merchant(UUID.randomUUID(), "Shop", None, None, None, reviewed = true, Instant.EPOCH)

  private val filled = RefundFormData.initial.copy(
    amount = RefundFormData.initial.amount.updated(PositiveBigDecimal.validate("12.50")),
    date = RefundFormData.initial.date.updated(ExpLocalDate.validate("2026-01-15"))
  )

  "initial" should {
    "not be submittable" in {
      RefundFormData.initial.isValid must be(false)
      RefundFormData.initial.submitRequest must be(None)
    }
  }

  "submitRequest" should {
    "resolve a valid form" in {
      val form = filled.copy(
        merchantId = filled.merchantId.updated(ExpOptionalId.validate(store.id.toString)),
        categoryId = filled.categoryId.updated(ExpOptionalId.validate(foodId.toString)),
        notes = filled.notes.updated(ExpNotes.optionalValidator("  a note "))
      )
      form.isValid must be(true)
      form.submitRequest must be(
        Some(
          RefundFormData.Resolved(
            amount = BigDecimal("12.50"),
            date = LocalDate.parse("2026-01-15"),
            merchantId = Some(store.id),
            categoryId = Some(foodId),
            notes = Some("a note")
          )
        )
      )
    }

    "resolve blank notes to None" in {
      filled.copy(notes = filled.notes.updated(ExpNotes.optionalValidator("   "))).submitRequest.map(_.notes) must be(
        Some(None)
      )
    }

    "be None for a bad amount" in {
      filled.copy(amount = filled.amount.updated(PositiveBigDecimal.validate("abc"))).submitRequest must be(None)
      filled.copy(amount = filled.amount.updated(PositiveBigDecimal.validate("0"))).submitRequest must be(None)
    }

    "be None for a bad date" in {
      val form = filled.copy(date = filled.date.updated(ExpLocalDate.validate("2026-13-45")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for a malformed merchant id" in {
      val form = filled.copy(merchantId = filled.merchantId.updated(ExpOptionalId.validate("not-a-uuid")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for a malformed category id" in {
      val form = filled.copy(categoryId = filled.categoryId.updated(ExpOptionalId.validate("not-a-uuid")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for notes over the limit" in {
      val form = filled.copy(notes = filled.notes.updated(ExpNotes.optionalValidator("a" * 501)))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }
  }

  "fromRefund" should {
    "round-trip the refund's values" in {
      val refund = GetRefunds.Response.Refund(
        id = UUID.randomUUID(),
        amount = BigDecimal("7.25"),
        date = LocalDate.parse("2026-02-03"),
        merchantId = Some(store.id),
        merchantName = Some("Store"),
        categoryId = Some(foodId),
        categoryName = Some("Food"),
        expenseId = None,
        notes = Some("returned"),
        createdAt = Instant.EPOCH
      )
      val form = RefundFormData.fromRefund(refund)
      form.amount.inputValue must be("7.25")
      form.date.inputValue must be("2026-02-03")
      form.notes.inputValue must be("returned")
      form.merchantId.inputValue must be(store.id.toString)
      form.categoryId.inputValue must be(foodId.toString)
      form.submitRequest must be(
        Some(
          RefundFormData.Resolved(
            amount = refund.amount,
            date = refund.date,
            merchantId = refund.merchantId,
            categoryId = refund.categoryId,
            notes = refund.notes
          )
        )
      )
    }
  }

  "withMerchant" should {
    "set the merchant's default category" in {
      val form = filled.withMerchant(ExpOptionalId.validate(store.id.toString), List(store, shop))
      form.merchantId.valueOpt must be(Some(Some(store.id)))
      form.categoryId.valueOpt must be(Some(Some(foodId)))
    }

    "clear the category for a merchant without a default" in {
      val form = filled
        .copy(categoryId = filled.categoryId.updated(ExpOptionalId.validate(foodId.toString)))
        .withMerchant(ExpOptionalId.validate(shop.id.toString), List(store, shop))
      form.merchantId.valueOpt must be(Some(Some(shop.id)))
      form.categoryId.valueOpt must be(Some(None))
    }

    "clear both when the merchant is unset" in {
      val form = filled
        .withMerchant(ExpOptionalId.validate(store.id.toString), List(store))
        .withMerchant(ExpOptionalId.validate(""), List(store))
      form.merchantId.valueOpt must be(Some(None))
      form.categoryId.valueOpt must be(Some(None))
    }
  }
}
