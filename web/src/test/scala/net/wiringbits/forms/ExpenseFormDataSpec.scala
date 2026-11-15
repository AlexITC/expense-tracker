package net.wiringbits.forms

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.ui.forms.{ExpLocalDate, ExpLocalTime, ExpNotes, ExpOptionalId, PositiveBigDecimal}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

class ExpenseFormDataSpec extends AnyWordSpec {

  private val filled = ExpenseFormData.initial.copy(
    amount = ExpenseFormData.initial.amount.updated(PositiveBigDecimal.validate("12.50")),
    date = ExpenseFormData.initial.date.updated(ExpLocalDate.validate("2026-01-15"))
  )

  private def withSplit(form: ExpenseFormData, categoryId: String, amount: String): ExpenseFormData = {
    val added = form.addSplit
    val key = added.splits.last.key
    added.updateSplit(
      key,
      r =>
        r.copy(
          category = r.category.updated(ExpOptionalId.validate(categoryId)),
          amount = r.amount.updated(PositiveBigDecimal.validate(amount))
        )
    )
  }

  private def expense(
      time: Option[LocalTime] = None,
      merchantId: Option[UUID] = None,
      notes: Option[String] = None,
      splits: List[GetExpenses.Response.Split] = Nil
  ) =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = BigDecimal("7.25"),
      date = LocalDate.parse("2026-02-03"),
      time = time,
      merchantId = merchantId,
      merchantName = merchantId.map(_ => "Store"),
      notes = notes,
      splits = splits,
      createdAt = Instant.EPOCH
    )

  "initial" should {
    "not be submittable" in {
      ExpenseFormData.initial.isValid must be(false)
      ExpenseFormData.initial.submitRequest must be(None)
    }
  }

  "submitRequest" should {
    "resolve a valid form to typed values" in {
      val merchantId = UUID.randomUUID()
      val categoryId = UUID.randomUUID()
      val form = withSplit(
        filled.copy(
          time = filled.time.updated(ExpLocalTime.optionalValidator("10:30")),
          merchantId = filled.merchantId.updated(ExpOptionalId.validate(merchantId.toString)),
          notes = filled.notes.updated(ExpNotes.optionalValidator("  a note "))
        ),
        categoryId = categoryId.toString,
        amount = "12.50"
      )
      form.isValid must be(true)
      form.submitRequest must be(
        Some(
          ExpenseFormData.Resolved(
            amount = BigDecimal("12.50"),
            date = LocalDate.parse("2026-01-15"),
            time = Some(LocalTime.of(10, 30)),
            merchantId = Some(merchantId),
            notes = Some("a note"),
            splits = List(Some(categoryId) -> BigDecimal("12.50"))
          )
        )
      )
    }

    "resolve blank optionals to None" in {
      val form = filled.copy(
        time = filled.time.updated(ExpLocalTime.optionalValidator(" ")),
        merchantId = filled.merchantId.updated(ExpOptionalId.validate("")),
        notes = filled.notes.updated(ExpNotes.optionalValidator("   "))
      )
      form.submitRequest.map(r => (r.time, r.merchantId, r.notes, r.splits)) must be(Some((None, None, None, Nil)))
    }

    "keep a split row with no category" in {
      withSplit(filled, categoryId = "", amount = "12.50").submitRequest.map(_.splits) must be(
        Some(List(None -> BigDecimal("12.50")))
      )
    }

    "resolve multiple split rows in row order" in {
      val catA = UUID.randomUUID()
      val catB = UUID.randomUUID()
      val form = withSplit(
        withSplit(filled, categoryId = catA.toString, amount = "4.50"),
        categoryId = catB.toString,
        amount = "8"
      )
      form.isValid must be(true)
      form.submitRequest.map(_.splits) must be(
        Some(List(Some(catA) -> BigDecimal("4.50"), Some(catB) -> BigDecimal("8")))
      )
    }

    "be None when a later split row is invalid" in {
      val form = withSplit(
        withSplit(filled, categoryId = UUID.randomUUID().toString, amount = "4.50"),
        categoryId = UUID.randomUUID().toString,
        amount = "abc"
      )
      form.isValid must be(false)
      form.submitRequest must be(None)
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

    "be None for an invalid time" in {
      val form = filled.copy(time = filled.time.updated(ExpLocalTime.optionalValidator("25:99")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for a malformed merchant id" in {
      val form = filled.copy(merchantId = filled.merchantId.updated(ExpOptionalId.validate("not-a-uuid")))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for over-limit notes" in {
      val form = filled.copy(notes = filled.notes.updated(ExpNotes.optionalValidator("x" * (ExpNotes.MaxLength + 1))))
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for an invalid split amount" in {
      val form = withSplit(filled, categoryId = UUID.randomUUID().toString, amount = "-3")
      form.isValid must be(false)
      form.submitRequest must be(None)
    }

    "be None for a malformed split category" in {
      val form = withSplit(filled, categoryId = "bogus", amount = "12.50")
      form.isValid must be(false)
      form.submitRequest must be(None)
    }
  }

  "fields" should {
    "cover the split rows" in {
      val added = filled.addSplit
      added.isValid must be(false)
      added.submitRequest must be(None)
      added.fields must contain allElementsOf added.splits.flatMap(r => List(r.category, r.amount))
    }
  }

  "addSplit" should {
    "use a fresh key after removeSplit" in {
      val two = filled.addSplit.addSplit
      two.splits.map(_.key) must be(List(0, 1))
      val removed = two.removeSplit(0)
      removed.splits.map(_.key) must be(List(1))
      val readded = removed.addSplit
      readded.splits.map(_.key) must be(List(1, 2))
      readded.splits.map(_.amount.name).distinct.size must be(2)
    }

    "name the row fields with the form prefix" in {
      val row = filled.addSplit.splits.head
      row.category.name must be("expense-create-split-0-category")
      row.amount.name must be("expense-create-split-0-amount")
    }
  }

  "fromExpense" should {
    "seed the raw input values" in {
      val merchantId = UUID.randomUUID()
      val categoryId = UUID.randomUUID()
      val e = expense(
        time = Some(LocalTime.of(9, 15)),
        merchantId = Some(merchantId),
        notes = Some(" lunch "),
        splits = List(GetExpenses.Response.Split(UUID.randomUUID(), categoryId, BigDecimal("7.25")))
      )
      val form = ExpenseFormData.fromExpense(e)
      form.amount.inputValue must be("7.25")
      form.date.inputValue must be("2026-02-03")
      form.time.inputValue must be("09:15")
      form.merchantId.inputValue must be(merchantId.toString)
      form.notes.inputValue must be(" lunch ")
      form.splits.map(r => (r.category.inputValue, r.amount.inputValue)) must be(List(categoryId.toString -> "7.25"))
      form.amount.name must be("expense-edit-amount")
      form.submitRequest must be(
        Some(
          ExpenseFormData.Resolved(
            amount = e.amount,
            date = e.date,
            time = e.time,
            merchantId = Some(merchantId),
            notes = Some("lunch"),
            splits = List(Some(categoryId) -> BigDecimal("7.25"))
          )
        )
      )
    }

    "seed blanks for missing optional values" in {
      val form = ExpenseFormData.fromExpense(expense())
      (form.time.inputValue, form.merchantId.inputValue, form.notes.inputValue, form.splits) must be(("", "", "", Nil))
      form.submitRequest.map(r => (r.time, r.merchantId, r.notes)) must be(Some((None, None, None)))
    }
  }
}
