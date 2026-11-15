package net.wiringbits.forms

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.ui.forms.{ExpLocalDate, PositiveBigDecimal}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

class ExpenseFormDataSpec extends AnyWordSpec {

  private val filled = ExpenseFormData.initial.copy(
    amount = ExpenseFormData.initial.amount.updated(PositiveBigDecimal.validate("12.50")),
    date = ExpenseFormData.initial.date.updated(ExpLocalDate.validate("2026-01-15"))
  )

  "initial" should {
    "not be submittable" in {
      ExpenseFormData.initial.isValid must be(false)
      ExpenseFormData.initial.submitRequest must be(None)
    }
  }

  "submitRequest" should {
    "resolve a valid form" in {
      val merchantId = UUID.randomUUID().toString
      val categoryId = UUID.randomUUID().toString
      val form = filled.copy(
        time = "10:30",
        merchantId = merchantId,
        notes = "  a note ",
        splits = List(categoryId -> "12.50")
      )
      form.isValid must be(true)
      form.submitRequest must be(
        Some(
          ExpenseFormData.Resolved(
            amount = BigDecimal("12.50"),
            date = LocalDate.parse("2026-01-15"),
            time = "10:30",
            merchantId = merchantId,
            notes = Some("a note"),
            splits = List(categoryId -> "12.50")
          )
        )
      )
    }

    "resolve blank notes to None" in {
      filled.copy(notes = "   ").submitRequest.map(_.notes) must be(Some(None))
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
  }

  "fromExpense" should {
    "seed the expense's values" in {
      val merchantId = UUID.randomUUID()
      val categoryId = UUID.randomUUID()
      val expense = GetExpenses.Response.Expense(
        id = UUID.randomUUID(),
        amount = BigDecimal("7.25"),
        date = LocalDate.parse("2026-02-03"),
        time = Some(LocalTime.of(9, 15)),
        merchantId = Some(merchantId),
        merchantName = Some("Store"),
        notes = Some("lunch"),
        splits = List(GetExpenses.Response.Split(UUID.randomUUID(), categoryId, BigDecimal("7.25"))),
        createdAt = Instant.EPOCH
      )
      val form = ExpenseFormData.fromExpense(expense)
      form.amount.inputValue must be("7.25")
      form.date.inputValue must be("2026-02-03")
      form.time must be("09:15")
      form.merchantId must be(merchantId.toString)
      form.notes must be("lunch")
      form.splits must be(List(categoryId.toString -> "7.25"))
      form.submitRequest.map(r => (r.amount, r.date, r.notes)) must be(
        Some((expense.amount, expense.date, expense.notes))
      )
    }

    "seed blanks for missing optional values" in {
      val expense = GetExpenses.Response.Expense(
        id = UUID.randomUUID(),
        amount = BigDecimal("3"),
        date = LocalDate.parse("2026-02-03"),
        time = None,
        merchantId = None,
        merchantName = None,
        notes = None,
        splits = Nil,
        createdAt = Instant.EPOCH
      )
      val form = ExpenseFormData.fromExpense(expense)
      (form.time, form.merchantId, form.notes, form.splits) must be(("", "", "", Nil))
    }
  }
}
