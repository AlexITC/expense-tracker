package net.wiringbits.actions.categories

import net.wiringbits.api.models.categories.UpdateCategory
import net.wiringbits.repositories.CategoriesRepository
import net.wiringbits.validations.ValidateCategoryFields

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit ec: ExecutionContext) {

  def apply(categoryId: UUID, userId: UUID, request: UpdateCategory.Request): Future[UpdateCategory.Response] = {
    for {
      description <- ValidateCategoryFields(name = request.name, description = request.description)
      _ <- categoriesRepository.update(
        id = categoryId,
        userId = userId,
        request.name,
        description = description.map(_.string)
      )
    } yield UpdateCategory.Response()
  }
}
