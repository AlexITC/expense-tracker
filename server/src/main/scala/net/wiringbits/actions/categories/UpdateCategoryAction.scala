package net.wiringbits.actions.categories

import net.wiringbits.api.models.categories.UpdateCategory
import net.wiringbits.repositories.CategoriesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit ec: ExecutionContext) {

  def apply(categoryId: UUID, userId: UUID, request: UpdateCategory.Request): Future[UpdateCategory.Response] = {
    for {
      _ <- validate(request)
      // The request carries no description yet, so keep the stored one instead of clearing it. A missing row still
      // fails in the UPDATE below with "Category not found".
      existing <- categoriesRepository.find(id = categoryId, userId = userId)
      _ <- categoriesRepository.update(
        id = categoryId,
        userId = userId,
        request.name,
        description = existing.flatMap(_.description)
      )
    } yield UpdateCategory.Response()
  }

  private def validate(request: UpdateCategory.Request): Future[Unit] = {
    // Existence/ownership is enforced by the UPDATE's WHERE id = ? AND user_id = ? (CategoryDAO.update throws
    // "Category not found" when no row matches), so there is no pre-read here.
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 25) Future.failed(new RuntimeException("Name must be 25 characters or less"))
    else Future.unit
  }
}
