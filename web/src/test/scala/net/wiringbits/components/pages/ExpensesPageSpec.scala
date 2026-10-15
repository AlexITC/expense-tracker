package net.wiringbits.components.pages

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.expenses.GetExpenses.Response.Split
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

class ExpensesPageSpec extends AnyWordSpec {

  private def expense(
      amount: BigDecimal,
      date: LocalDate = LocalDate.of(2024, 1, 1),
      time: Option[LocalTime] = None,
      splits: List[Split] = Nil
  ): GetExpenses.Response.Expense =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = amount,
      date = date,
      time = time,
      merchantId = None,
      merchantName = None,
      notes = None,
      splits = splits,
      createdAt = Instant.now()
    )

  "sortExpenses" should {
    "sort by date descending, breaking ties by time descending with None last" in {
      val jan2NoTime = expense(BigDecimal("1"), date = LocalDate.of(2024, 1, 2))
      val jan1Morning = expense(BigDecimal("2"), date = LocalDate.of(2024, 1, 1), time = Some(LocalTime.of(9, 0)))
      val jan1NoTime = expense(BigDecimal("3"), date = LocalDate.of(2024, 1, 1))

      val result = ExpenseList.sortExpenses(List(jan1Morning, jan1NoTime, jan2NoTime))
      result must be(List(jan2NoTime, jan1Morning, jan1NoTime))
    }

    "sort same-date expenses with times by time descending" in {
      val morning = expense(BigDecimal("1"), date = LocalDate.of(2024, 1, 1), time = Some(LocalTime.of(9, 0)))
      val evening = expense(BigDecimal("2"), date = LocalDate.of(2024, 1, 1), time = Some(LocalTime.of(17, 0)))

      val result = ExpenseList.sortExpenses(List(morning, evening))
      result must be(List(evening, morning))
    }
  }

  "applyDateFilter" should {
    // Anchor: 2024-03-15 (Friday, mid-month)
    val today = LocalDate.of(2024, 3, 15)

    // today
    val todayExp = expense(BigDecimal("1"), date = LocalDate.of(2024, 3, 15))
    // this week (within 6 days before today)
    val day3Ago = expense(BigDecimal("2"), date = LocalDate.of(2024, 3, 12))
    val day6Ago = expense(BigDecimal("3"), date = LocalDate.of(2024, 3, 9))
    // exactly 7 days ago — outside the rolling window
    val day7Ago = expense(BigDecimal("4"), date = LocalDate.of(2024, 3, 8))
    // this month but older than a week
    val mar1 = expense(BigDecimal("5"), date = LocalDate.of(2024, 3, 1))
    // previous month
    val feb28 = expense(BigDecimal("6"), date = LocalDate.of(2024, 2, 28))
    // two months ago
    val jan15 = expense(BigDecimal("7"), date = LocalDate.of(2024, 1, 15))
    // future — must be excluded from week and month filters
    val tomorrow = expense(BigDecimal("8"), date = LocalDate.of(2024, 3, 16))
    val nextMonth = expense(BigDecimal("9"), date = LocalDate.of(2024, 4, 1))

    val all =
      List(todayExp, day3Ago, day6Ago, day7Ago, mar1, feb28, jan15, tomorrow, nextMonth)

    "return only today's expenses for 'today'" in {
      ExpenseList.applyDateFilter(all, "today", today) must be(List(todayExp))
    }

    "return expenses within the last 7 days for 'week', excluding day 7 and future dates" in {
      ExpenseList.applyDateFilter(all, "week", today) must be(List(todayExp, day3Ago, day6Ago))
    }

    "return expenses from the first of the current month for 'month', excluding future dates" in {
      ExpenseList.applyDateFilter(all, "month", today) must be(
        List(todayExp, day3Ago, day6Ago, day7Ago, mar1)
      )
    }

    "return all expenses for 'all'" in {
      ExpenseList.applyDateFilter(all, "all", today) must be(all)
    }
  }

  "isFilterActive" should {
    "return false when all filters are at their defaults" in {
      ExpenseList.isFilterActive("month", "") must be(false)
    }

    "return true when dateFilter is not month" in {
      ExpenseList.isFilterActive("today", "") must be(true)
      ExpenseList.isFilterActive("week", "") must be(true)
      ExpenseList.isFilterActive("all", "") must be(true)
    }

    "return true when filterText is non-empty" in {
      ExpenseList.isFilterActive("month", "coffee") must be(true)
    }
  }

  "formatAmount" should {
    "format a whole number with two decimal places" in {
      ExpenseList.formatAmount(BigDecimal("5")) must be("5.00")
    }

    "format a one-decimal value with two decimal places" in {
      ExpenseList.formatAmount(BigDecimal("5.1")) must be("5.10")
    }

    "format a negative value" in {
      ExpenseList.formatAmount(BigDecimal("-3.50")) must be("-3.50")
    }

    "round a value with more than two decimal places using HALF_UP" in {
      ExpenseList.formatAmount(BigDecimal("1.235")) must be("1.24")
      ExpenseList.formatAmount(BigDecimal("1.234")) must be("1.23")
    }
  }

  "applyDeletedIds" should {
    val idA = UUID.randomUUID()
    val idB = UUID.randomUUID()
    val idC = UUID.randomUUID()
    val expA = expense(BigDecimal("1"))
    val expB = expense(BigDecimal("2"))
    val expC = expense(BigDecimal("3"))
    val all = List(expA.copy(id = idA), expB.copy(id = idB), expC.copy(id = idC))

    "return all expenses when deletedIds is empty" in {
      ExpenseList.applyDeletedIds(all, Set.empty) must be(all)
    }

    "filter out a single deleted id" in {
      ExpenseList.applyDeletedIds(all, Set(idB)) must be(List(all(0), all(2)))
    }

    "filter out multiple deleted ids" in {
      ExpenseList.applyDeletedIds(all, Set(idA, idC)) must be(List(all(1)))
    }
  }
}
