package net.wiringbits.actions.categories

import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.repositories.CategoriesRepository
import net.wiringbits.validations.{ValidateCategoryDescriptionKept, ValidateCategoryFields}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit
    ec: ExecutionContext
) {

  // Create is idempotent on name: when the category already exists, the existing row is returned unchanged. A
  // submitted description that differs from the stored one fails instead of being dropped; descriptions of existing
  // categories are changed through UpdateCategoryAction.
  def apply(userId: UUID, request: CreateCategory.Request): Future[CreateCategory.Response] = {
    for {
      description <- ValidateCategoryFields(name = request.name, description = request.description)
      category <- categoriesRepository.getOrCreate(userId, request.name, description = description.map(_.string))
      _ <- ValidateCategoryDescriptionKept(category, description)
    } yield CreateCategory.Response(id = category.id, name = category.name)
  }
}
