package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.CreateExpenseFromRow
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class BulkExpenseCreationPageSpec extends AnyWordSpec {

  "importError" should {

    "fail when no rows are selected" in {
      BulkExpenseCreationPage.importError(Nil, "Groceries") mustBe Some("No rows selected")
    }

    "fail when the category is blank" in {
      BulkExpenseCreationPage.importError(List(UUID.randomUUID()), "  ") mustBe Some("Choose a category")
    }

    "pass when rows are selected and a category is chosen" in {
      BulkExpenseCreationPage.importError(List(UUID.randomUUID()), "Groceries") mustBe None
    }
  }

  "rowSplits" should {

    "allocate the full absolute row amount to the category" in {
      val catId = UUID.randomUUID()
      BulkExpenseCreationPage.rowSplits(catId, BigDecimal("-42.50")) mustBe
        List(CreateExpenseFromRow.Split(catId, BigDecimal("42.50")))
    }
  }
}
