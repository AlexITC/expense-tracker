package net.wiringbits.components.pages

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID
import scala.scalajs.js

class AutocompleteLabelsSpec extends AnyWordSpec {

  private val merchant = GetMerchants.Response.Merchant(
    id = UUID.randomUUID(),
    name = "Carniceria El Riscu",
    notes = None,
    categoryId = None,
    taxId = None,
    reviewed = false,
    createdAt = Instant.now()
  )

  private val category = GetCategories.Response.Category(
    id = UUID.randomUUID(),
    name = "Food",
    createdAt = Instant.now()
  )

  "merchantOptionLabel" should {
    "return name from a Merchant instance" in {
      AutocompleteLabels.merchantOptionLabel(merchant.asInstanceOf[js.Any]) mustBe "Carniceria El Riscu"
    }
    "return the string as-is for freeSolo text input" in {
      AutocompleteLabels.merchantOptionLabel("typed text".asInstanceOf[js.Any]) mustBe "typed text"
    }
    "return empty string for undefined (MUI internal value on first render)" in {
      val undef: js.Any = js.undefined.asInstanceOf[js.Any]
      AutocompleteLabels.merchantOptionLabel(undef) mustBe ""
    }
    "return empty string for null" in {
      AutocompleteLabels.merchantOptionLabel(null.asInstanceOf[js.Any]) mustBe ""
    }
    "return empty string for an unrelated object (e.g. a Category)" in {
      AutocompleteLabels.merchantOptionLabel(category.asInstanceOf[js.Any]) mustBe ""
    }
  }

  "categoryOptionLabel" should {
    "return name from a Category instance" in {
      AutocompleteLabels.categoryOptionLabel(category.asInstanceOf[js.Any]) mustBe "Food"
    }
    "return the string as-is for freeSolo text input" in {
      AutocompleteLabels.categoryOptionLabel("Groceries".asInstanceOf[js.Any]) mustBe "Groceries"
    }
    "return empty string for undefined" in {
      val undef: js.Any = js.undefined.asInstanceOf[js.Any]
      AutocompleteLabels.categoryOptionLabel(undef) mustBe ""
    }
    "return empty string for null" in {
      AutocompleteLabels.categoryOptionLabel(null.asInstanceOf[js.Any]) mustBe ""
    }
  }
}
