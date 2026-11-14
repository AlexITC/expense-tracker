package net.wiringbits.actions.categories

import net.wiringbits.api.models.categories.UpdateCategory
import net.wiringbits.repositories.CategoriesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit ec: ExecutionContext) {

  def apply(categoryId: UUID, userId: UUID, request: UpdateCategory.Request): Future[UpdateCategory.Response] = {
    // A present-but-empty description means "no description"; the primitive already enforces the length limit.
    val description = request.description.filter(_.string.nonEmpty)
    for {
      _ <- categoriesRepository.update(
        id = categoryId,
        userId = userId,
        request.name.string,
        description = description.map(_.string)
      )
    } yield UpdateCategory.Response()
  }
}
