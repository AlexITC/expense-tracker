package net.wiringbits.actions.categories

import net.wiringbits.api.models.categories.DeleteCategory
import net.wiringbits.repositories.CategoriesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit ec: ExecutionContext) {

  def apply(categoryId: UUID, userId: UUID): Future[DeleteCategory.Response] = {
    for {
      existing <- categoriesRepository.find(categoryId, userId)
      _ = if (existing.isEmpty) throw new RuntimeException("Category not found")
      _ <- categoriesRepository.delete(categoryId, userId)
    } yield DeleteCategory.Response()
  }
}
