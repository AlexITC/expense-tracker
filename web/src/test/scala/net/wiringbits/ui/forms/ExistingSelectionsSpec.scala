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

    "attach the given id to a stored merchant or category name" in {
      val id = UUID.randomUUID()
      ExistingSelections.existingMerchant("Store", id).toOption.map(s => (s.value.string, s.id)) must be(
        Some(("Store", Some(id)))
      )
      ExistingSelections.existingCategory("Food", id).toOption.map(s => (s.value.string, s.id)) must be(
        Some(("Food", Some(id)))
      )
    }

    "build a new selection without an id from raw text" in {
      ExistingSelections.newCategory("Food").toOption.map(s => (s.value.string, s.id)) must be(Some(("Food", None)))
    }
  }

  "defaultCategory" should {
    val food = category("Food")
    val store = merchant("Store").copy(categoryId = Some(food.id))
    def picked(m: GetMerchants.Response.Merchant) = Some(ExistingOrNewValue(MerchantName.trusted(m.name), Some(m.id)))

    "return the default category of an existing merchant" in {
      ExistingSelections.defaultCategory(picked(store), List(store), List(food)) must be(Some(food))
    }

    "return none for a new merchant without an id" in {
      val typed = Some(ExistingOrNewValue[MerchantName, UUID](MerchantName.trusted("Store"), None))
      ExistingSelections.defaultCategory(typed, List(store), List(food)) must be(None)
    }

    "return none when the merchant has no default category" in {
      val shop = merchant("Shop")
      ExistingSelections.defaultCategory(picked(shop), List(shop), List(food)) must be(None)
    }

    "return none when the default category is not in the list" in {
      ExistingSelections.defaultCategory(picked(store), List(store), Nil) must be(None)
    }

    "return none when nothing is picked" in {
      ExistingSelections.defaultCategory(None, List(store), List(food)) must be(None)
    }
  }

  "defaultCategoryId" should {
    "return the default category id of a known merchant, or none" in {
      val foodId = UUID.randomUUID()
      val store = merchant("Store").copy(categoryId = Some(foodId))
      ExistingSelections.defaultCategoryId(Some(store.id), List(store)) must be(Some(foodId))
      ExistingSelections.defaultCategoryId(Some(UUID.randomUUID()), List(store)) must be(None)
      ExistingSelections.defaultCategoryId(None, List(store)) must be(None)
    }
  }
}
