package net.wiringbits.components.pages

import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.ExpenseSplits
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class ExpenseFormSplitsSpec extends AnyWordSpec {

  "splitsError" should {
    val msg = Some(ExpenseSplits.NotFullyAllocatedError)

    "return an error when there are no splits" in {
      ExpenseFormSplits.splitsError(BigDecimal("10"), Nil) must be(msg)
    }

    "return an error when the splits leave a remainder" in {
      ExpenseFormSplits.splitsError(BigDecimal("10"), List(BigDecimal("4"), BigDecimal("5"))) must be(msg)
    }

    "return an error when the splits exceed the amount" in {
      ExpenseFormSplits.splitsError(BigDecimal("10"), List(BigDecimal("6"), BigDecimal("5"))) must be(msg)
    }

    "return None when a single split covers the amount" in {
      ExpenseFormSplits.splitsError(BigDecimal("10"), List(BigDecimal("10.00"))) must be(None)
    }

    "return None when multiple splits sum to the amount" in {
      ExpenseFormSplits.splitsError(BigDecimal("10"), List(BigDecimal("3.50"), BigDecimal("6.50"))) must be(None)
    }
  }

  "createSplits" should {
    val catA = UUID.randomUUID()
    val catB = UUID.randomUUID()
    val merchant = GetMerchants.Response.Merchant(
      id = UUID.randomUUID(),
      name = "Shop",
      notes = None,
      categoryId = Some(catA),
      reviewed = true,
      createdAt = Instant.now()
    )
    val uncategorized = merchant.copy(id = UUID.randomUUID(), categoryId = None)
    val merchants = List(merchant, uncategorized)

    "fail when there are no splits and no merchant" in {
      ExpenseFormSplits.createSplits(BigDecimal("10"), Nil, merchants, "") must be(
        Left(ExpenseSplits.NotFullyAllocatedError)
      )
    }

    "fail when there are no splits and the merchant has no default category" in {
      ExpenseFormSplits.createSplits(BigDecimal("10"), Nil, merchants, uncategorized.id.toString) must be(
        Left(ExpenseSplits.NotFullyAllocatedError)
      )
    }

    "fail when the explicit splits leave a remainder, even if the merchant has a default category" in {
      ExpenseFormSplits.createSplits(
        BigDecimal("10"),
        List(catB.toString -> "4"),
        merchants,
        merchant.id.toString
      ) must be(
        Left(ExpenseSplits.NotFullyAllocatedError)
      )
    }

    "use the merchant default category for the full amount when there are no splits" in {
      ExpenseFormSplits.createSplits(BigDecimal("10"), Nil, merchants, merchant.id.toString) must be(
        Right(List(CreateExpense.Split(catA, BigDecimal("10"))))
      )
    }

    "send the explicit splits when they cover the amount" in {
      val form = List(catA.toString -> "3.50", catB.toString -> "6.50")
      ExpenseFormSplits.createSplits(BigDecimal("10"), form, merchants, merchant.id.toString) must be(
        Right(List(CreateExpense.Split(catA, BigDecimal("3.50")), CreateExpense.Split(catB, BigDecimal("6.50"))))
      )
    }
  }
}
