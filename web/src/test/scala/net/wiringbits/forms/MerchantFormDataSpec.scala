package net.wiringbits.forms

import net.wiringbits.api.models.merchants.GetMerchants
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class MerchantFormDataSpec extends AnyWordSpec {

  private val categoryId = UUID.randomUUID()

  private def form(
      name: Option[String] = Some("Corner Store"),
      notes: String = "  weekly groceries  ",
      categoryId: String = categoryId.toString,
      taxId: String = " ABC123 "
  ): MerchantFormData =
    MerchantFormData.initialCreate(
      name = name,
      notes = notes,
      categoryId = categoryId,
      taxId = taxId
    )

  "initial" should {
    "produce an invalid form when blank" in {
      val blank = MerchantFormData.blankCreate
      blank.isValid must be(false)
      blank.submitRequest must be(None)
    }

    "keep an over-limit tax id as raw input and be invalid" in {
      val raw = "T" * 51
      val edit = MerchantFormData.fromMerchant(
        GetMerchants.Response.Merchant(
          id = UUID.randomUUID(),
          name = "Corner Store",
          notes = None,
          categoryId = None,
          taxId = Some(raw),
          reviewed = false,
          createdAt = Instant.EPOCH
        )
      )
      edit.taxId.inputValue must be(raw)
      edit.isValid must be(false)
    }

    "prefix the optional field names" in {
      val f = form()
      f.notes.name must be("merchant-create-notes")
      f.categoryId.name must be("merchant-create-categoryId")
      f.taxId.name must be("merchant-create-taxId")
    }

    "prefix the edit form's field names differently from the create form" in {
      MerchantFormData.blankEdit.notes.name must be("merchant-edit-notes")
      MerchantFormData.blankEdit.categoryId.name must be("merchant-edit-categoryId")
      MerchantFormData.blankEdit.taxId.name must be("merchant-edit-taxId")
    }
  }

  "submitRequest" should {
    "trim notes and tax id and parse the category id" in {
      val request = form().submitRequest.getOrElse(fail("expected a request"))
      request.name.string must be("Corner Store")
      request.notes must be(Some("weekly groceries"))
      request.categoryId must be(Some(categoryId))
      request.taxId must be(Some("ABC123"))
    }

    "map blank optional fields to None" in {
      val request =
        form(notes = "   ", categoryId = "", taxId = "  ").submitRequest.getOrElse(fail("expected a request"))
      request.notes must be(None)
      request.categoryId must be(None)
      request.taxId must be(None)
    }

    "be None without a name" in {
      form(name = None).submitRequest must be(None)
    }

    "accept notes at the 255-char server limit" in {
      form(notes = "n" * 255).submitRequest.flatMap(_.notes) must be(Some("n" * 255))
    }

    "be None for notes over the 255-char server limit" in {
      val f = form(notes = "n" * 256)
      f.isValid must be(false)
      f.submitRequest must be(None)
      f.updateRequest must be(None)
    }

    "be None for an over-limit tax id" in {
      val f = form(taxId = "T" * 51)
      f.isValid must be(false)
      f.submitRequest must be(None)
      f.updateRequest must be(None)
    }

    "be None for a malformed category id" in {
      val f = form(categoryId = "not-a-uuid")
      f.isValid must be(false)
      f.submitRequest must be(None)
      f.updateRequest must be(None)
    }
  }

  "updateRequest" should {
    "mirror the create values" in {
      val f = form()
      val create = f.submitRequest.getOrElse(fail("expected a request"))
      val update = f.updateRequest.getOrElse(fail("expected a request"))
      update.name.string must be(create.name.string)
      update.notes must be(create.notes)
      update.categoryId must be(create.categoryId)
      update.taxId must be(create.taxId)
    }
  }
}
