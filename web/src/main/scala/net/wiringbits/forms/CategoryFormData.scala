package net.wiringbits.forms

import net.wiringbits.api.models.categories.{CreateCategory, UpdateCategory}
import net.wiringbits.common.models.{CategoryDescription, CategoryName}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

case class CategoryFormData(
    name: FormField[CategoryName],
    description: FormField[CategoryDescription]
) extends FormData[CreateCategory.Request] {
  override def fields: List[FormField[?]] = List(name, description)

  override def formValidationErrors: List[String] = {
    List(
      fieldsError
    ).flatten
  }

  override def submitRequest: Option[CreateCategory.Request] = {
    val formData = this
    for {
      name <- formData.name.valueOpt
      description <- CategoryDescription.parseOptional(formData.description.value.map(_.input)).toOption
    } yield CreateCategory.Request(
      name = name.string,
      description = description.map(_.string)
    )
  }

  def updateRequest: Option[UpdateCategory.Request] =
    submitRequest.map(request => UpdateCategory.Request(name = request.name, description = request.description))
}

object CategoryFormData {

  def initial(
      nameLabel: String,
      descriptionLabel: String,
      name: Option[CategoryName],
      description: Option[CategoryDescription]
  ): CategoryFormData = CategoryFormData(
    name = new FormField(
      label = nameLabel,
      name = "name",
      required = true,
      value = name.map(x => ValidationResult.Valid(x.string, x))
    ),
    description = new FormField(
      label = descriptionLabel,
      name = "description",
      value = description.map(x => ValidationResult.Valid(x.string, x))
    )
  )
}
