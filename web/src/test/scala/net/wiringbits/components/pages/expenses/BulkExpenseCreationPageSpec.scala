package net.wiringbits.components.pages.expenses

import net.wiringbits.api.models.bankstatements.CreateExpenseFromRow
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class BulkExpenseCreationPageSpec extends AnyWordSpec {

  "importError" should {

    "fail when no rows are selected" in {
      BulkExpenseCreationPage.importError(Nil) mustBe Some("No rows selected")
    }

    "pass when rows are selected" in {
      BulkExpenseCreationPage.importError(List(UUID.randomUUID())) mustBe None
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
