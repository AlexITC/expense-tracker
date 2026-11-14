package net.wiringbits.forms

import net.wiringbits.api.models.categories.{CreateCategory, UpdateCategory}
import net.wiringbits.common.models.{CategoryDescription, CategoryName}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class CategoryFormDataSpec extends AnyWordSpec {

  private val initialForm = CategoryFormData.initial(
    nameLabel = "Name",
    descriptionLabel = "Description",
    name = None,
    description = None
  )

  private val nameOnlyForm = initialForm.copy(name = initialForm.name.updated(CategoryName.validate("Groceries")))

  private def withDescription(text: String): CategoryFormData =
    nameOnlyForm.copy(description = nameOnlyForm.description.updated(CategoryDescription.validate(text)))

  private val overLimitDescriptionForm = withDescription("x" * (CategoryDescription.MaxLength + 1))

  private val blankNameForm = initialForm.copy(name = initialForm.name.updated(CategoryName.validate("  ")))

  "fields" should {
    "return the expected fields" in {
      initialForm.fields.map(_.name) must be(List("name", "description"))
    }
  }

  "formValidationErrors" should {
    "return no errors when the name is valid and the description is untouched" in {
      nameOnlyForm.formValidationErrors must be(empty)
    }

    "return an error when the description is over the limit" in {
      overLimitDescriptionForm.formValidationErrors must not be empty
    }

    "return an error when the name is untouched" in {
      initialForm.formValidationErrors must not be empty
    }
  }

  "submitRequest" should {
    "return a request without description when only the name is set" in {
      nameOnlyForm.submitRequest must be(
        Some(CreateCategory.Request(name = CategoryName.trusted("Groceries"), description = None))
      )
    }

    "return a request without description when the description is blank" in {
      withDescription("   ").submitRequest must be(
        Some(CreateCategory.Request(name = CategoryName.trusted("Groceries"), description = None))
      )
    }

    "return a request with the trimmed description when it is valid" in {
      withDescription("  Food and household items  ").submitRequest must be(
        Some(
          CreateCategory.Request(
            name = CategoryName.trusted("Groceries"),
            description = Some(CategoryDescription.trusted("Food and household items"))
          )
        )
      )
    }

    "return None when the description is over the limit" in {
      overLimitDescriptionForm.submitRequest must be(None)
    }

    "return None when the name is blank" in {
      blankNameForm.submitRequest must be(None)
    }
  }

  "updateRequest" should {
    "mirror submitRequest" in {
      val form = withDescription("Food")
      form.updateRequest must be(
        Some(
          UpdateCategory.Request(
            name = CategoryName.trusted("Groceries"),
            description = Some(CategoryDescription.trusted("Food"))
          )
        )
      )
    }

    "send None for a cleared description" in {
      val form = CategoryFormData.initial(
        nameLabel = "Name",
        descriptionLabel = "Description",
        name = Some(CategoryName.trusted("Groceries")),
        description = Some(CategoryDescription.trusted("Food"))
      )
      val cleared = form.copy(description = form.description.updated(CategoryDescription.validate("")))
      cleared.updateRequest must be(
        Some(UpdateCategory.Request(name = CategoryName.trusted("Groceries"), description = None))
      )
    }
  }
}
