package net.wiringbits.validations

import net.wiringbits.common.models.CategoryDescription
import net.wiringbits.repositories.models.Category

import scala.concurrent.Future

object ValidateCategoryDescriptionKept {

  /** Get-or-create returns an existing category unchanged; fails when that would drop the submitted description. */
  def apply(category: Category, submitted: Option[CategoryDescription]): Future[Unit] =
    if (submitted.exists(d => !category.description.contains(d.string)))
      Future.failed(
        new RuntimeException("A category with that name already exists; edit it to change its description")
      )
    else Future.unit
}
