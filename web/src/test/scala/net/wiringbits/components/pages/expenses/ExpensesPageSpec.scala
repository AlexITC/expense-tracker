package net.wiringbits.components.pages.expenses

import net.wiringbits.api.models.expenses.{GetExpenses, MergeDetailsSource, UpdateExpense}
import net.wiringbits.common.ExpenseSplits
import net.wiringbits.api.models.expenses.GetExpenses.Response.Split
import net.wiringbits.utils.ExpenseDuplicateDetector
import net.wiringbits.utils.ExpenseDuplicateDetector.{Candidate, Tier}
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

  "applyMerges" should {
    val survivor = expense(BigDecimal("10"))
    val other = expense(BigDecimal("20")).copy(receiptIds = List(UUID.randomUUID()))
    val all = List(survivor, other)

    "return the expenses unchanged when there are no merges" in {
      ExpenseList.applyMerges(all, Map.empty) must be(all)
    }

    "add the merged receipt id to the survivor only" in {
      val receiptId = UUID.randomUUID()
      ExpenseList.applyMerges(all, Map(survivor.id -> receiptId)) must be(
        List(survivor.copy(receiptIds = List(receiptId)), other)
      )
    }

    "ignore merges whose survivor is not in the list" in {
      ExpenseList.applyMerges(all, Map(UUID.randomUUID() -> UUID.randomUUID())) must be(all)
    }
  }

  "applyMergeState" should {
    "remove a merged orphan and leave its survivor unchanged" in {
      val survivor = expense(BigDecimal("10")).copy(rowIds = List(UUID.randomUUID()))
      val orphan = expense(BigDecimal("10"))
      val other = expense(BigDecimal("20"))
      ExpenseList.applyMergeState(List(survivor, orphan, other), Set(orphan.id), Map.empty, Map.empty) must be(
        List(survivor, other)
      )
    }

    "leave no duplicate candidates for a merged survivor, including its ambiguous siblings" in {
      val survivor = expense(BigDecimal("10")).copy(rowIds = List(UUID.randomUUID()))
      val loser1 = expense(BigDecimal("10")).copy(receiptIds = List(UUID.randomUUID()))
      val loser2 = expense(BigDecimal("10")).copy(receiptIds = List(UUID.randomUUID()))
      val all = List(survivor, loser1, loser2)
      ExpenseDuplicateDetector.detect(all).size must be(2)

      val merged =
        ExpenseList.applyMergeState(all, Set(loser1.id), Map(survivor.id -> loser1.receiptIds.head), Map.empty)
      ExpenseDuplicateDetector.detect(merged) must be(Nil)
    }

    "apply the adopted details to the survivor after removing the loser and adding its receipt" in {
      val survivor = expense(BigDecimal("10")).copy(rowIds = List(UUID.randomUUID()))
      val loser = expense(BigDecimal("10"))
        .copy(receiptIds = List(UUID.randomUUID()), merchantName = Some("Shop"), notes = Some("lunch"))
      val receiptId = loser.receiptIds.head

      val result = ExpenseList.applyMergeState(
        List(survivor, loser),
        Set(loser.id),
        Map(survivor.id -> receiptId),
        Map(survivor.id -> loser)
      )
      result must be(
        List(survivor.copy(receiptIds = List(receiptId), merchantName = Some("Shop"), notes = Some("lunch")))
      )
    }
  }

  private def candidate(receiptId: Option[UUID]): Candidate =
    Candidate(
      survivor = expense(BigDecimal("10")).copy(rowIds = List(UUID.randomUUID())),
      loser = expense(BigDecimal("10")).copy(receiptIds = receiptId.toList),
      receiptId = receiptId,
      tier = Tier.Strong,
      ambiguous = false
    )

  "withMergedReceipt" should {
    "record the moved receipt on the survivor for a receipt candidate" in {
      val receiptId = UUID.randomUUID()
      val c = candidate(Some(receiptId))
      ExpenseList.withMergedReceipt(Map.empty, c) must be(Map(c.survivor.id -> receiptId))
    }

    "leave the merged receipts unchanged for an orphan candidate" in {
      val existing = Map(UUID.randomUUID() -> UUID.randomUUID())
      ExpenseList.withMergedReceipt(existing, candidate(None)) must be(existing)
    }
  }

  "mergeDialogLabels" should {
    "describe a merge for a receipt candidate" in {
      ExpenseList.mergeDialogLabels(candidate(Some(UUID.randomUUID()))) must be(("Merge expenses?", "Merge"))
    }

    "describe a delete for an orphan candidate" in {
      ExpenseList.mergeDialogLabels(candidate(None)) must be(("Delete duplicate?", "Delete"))
    }
  }

  "mergeSummary" should {
    "say the orphan is deleted and the bank-row expense keeps its details" in {
      val summary = ExpenseList.mergeSummary(candidate(None), MergeDetailsSource.Row)
      summary must include("no receipt")
      summary must include("will be deleted")
      summary must include("bank-row expense, which keeps its details")
    }

    "say the receipt moves for a receipt candidate" in {
      val summary = ExpenseList.mergeSummary(candidate(Some(UUID.randomUUID())), MergeDetailsSource.Receipt)
      summary must include("The receipt's details")
      summary must include("The receipt moves to the bank-row expense")
    }
  }

  "applyAdoptedDetails" should {
    val survivor = expense(
      BigDecimal("10"),
      date = LocalDate.of(2024, 3, 5),
      time = Some(LocalTime.of(12, 0)),
      splits = List(Split(UUID.randomUUID(), UUID.randomUUID(), BigDecimal("10")))
    ).copy(
      merchantId = Some(UUID.randomUUID()),
      merchantName = Some("Bank merchant"),
      notes = Some("bank"),
      receiptIds = List(UUID.randomUUID()),
      rowIds = List(UUID.randomUUID())
    )
    val loser = expense(
      BigDecimal("9"),
      date = LocalDate.of(2024, 3, 4),
      splits = List(Split(UUID.randomUUID(), UUID.randomUUID(), BigDecimal("9")))
    ).copy(merchantId = Some(UUID.randomUUID()), merchantName = Some("Receipt merchant"), notes = Some("receipt"))
    val other = expense(BigDecimal("20"))
    val all = List(survivor, other)

    "return the expenses unchanged when nothing was adopted" in {
      ExpenseList.applyAdoptedDetails(all, Map.empty) must be(all)
    }

    "copy merchant, notes and splits onto the survivor only, keeping its amount, date, time, receipts and rows" in {
      ExpenseList.applyAdoptedDetails(all, Map(survivor.id -> loser)) must be(
        List(
          survivor.copy(
            merchantId = loser.merchantId,
            merchantName = loser.merchantName,
            notes = loser.notes,
            splits = loser.splits
          ),
          other
        )
      )
    }
  }

  "updateSplits" should {
    val catA = UUID.randomUUID()
    val catB = UUID.randomUUID()

    "fail when there are no splits" in {
      ExpenseList.updateSplits(BigDecimal("10"), Nil) must be(Left(ExpenseSplits.NotFullyAllocatedError))
    }

    "fail when the splits leave a remainder" in {
      ExpenseList.updateSplits(BigDecimal("10"), List(catA.toString -> "4")) must be(
        Left(ExpenseSplits.NotFullyAllocatedError)
      )
    }

    "send the splits when they cover the amount" in {
      ExpenseList.updateSplits(BigDecimal("10"), List(catA.toString -> "4", catB.toString -> "6")) must be(
        Right(List(UpdateExpense.Split(catA, BigDecimal("4")), UpdateExpense.Split(catB, BigDecimal("6"))))
      )
    }
  }
}
