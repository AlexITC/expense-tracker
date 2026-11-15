package net.wiringbits.ui.forms

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.{CategoryName, MerchantName}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class ExistingSelectionsSpec extends AnyWordSpec {
  private def merchant(name: String) =
    GetMerchants.Response.Merchant(
      id = UUID.randomUUID(),
      name = name,
      notes = None,
      categoryId = None,
      taxId = None,
      reviewed = false,
      createdAt = Instant.now()
    )

  private def category(name: String) =
    GetCategories.Response.Category(id = UUID.randomUUID(), name = name, createdAt = Instant.now(), description = None)

  "ExistingSelections" should {
    "attach the id of an existing merchant" in {
      val m = merchant("Store")
      val result = ExistingSelections.merchant(m)
      result.toOption.map(s => (s.value.string, s.id)) must be(Some(("Store", Some(m.id))))
    }

    "reject an over-limit existing merchant name without truncating it" in {
      val name = "X" * (MerchantName.MaxLength + 1)
      val result = ExistingSelections.merchant(merchant(name))
      result.isValid must be(false)
      result.input must be(name)
    }

    "reject an over-limit existing category name without truncating it" in {
      val name = "X" * (CategoryName.MaxLength + 1)
      val result = ExistingSelections.category(category(name))
      result.isValid must be(false)
      result.input must be(name)
    }

    "build a new selection without an id from raw text" in {
      ExistingSelections.newCategory("Food").toOption.map(s => (s.value.string, s.id)) must be(Some(("Food", None)))
    }
  }
}
