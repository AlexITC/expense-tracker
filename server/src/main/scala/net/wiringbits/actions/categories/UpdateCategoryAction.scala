package net.wiringbits.actions.categories

import net.wiringbits.api.models.categories.UpdateCategory
import net.wiringbits.repositories.CategoriesRepository
import net.wiringbits.validations.ValidateCategoryNameIsAvailable

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit ec: ExecutionContext) {

  def apply(categoryId: UUID, userId: UUID, request: UpdateCategory.Request): Future[UpdateCategory.Response] = {
    for {
      _ <- validate(categoryId, userId, request)
      _ <- categoriesRepository.update(categoryId, userId, request.name)
    } yield UpdateCategory.Response()
  }

  private def validate(categoryId: UUID, userId: UUID, request: UpdateCategory.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 25) Future.failed(new RuntimeException("Name must be 25 characters or less"))
    else
      for {
        existing <- categoriesRepository.find(categoryId, userId)
        _ = if (existing.isEmpty) throw new RuntimeException("Category not found")
        _ <- ValidateCategoryNameIsAvailable(categoriesRepository, userId, request.name, excludeId = Some(categoryId))
      } yield ()
  }
}
