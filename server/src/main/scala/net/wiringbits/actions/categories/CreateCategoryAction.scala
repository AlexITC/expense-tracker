package net.wiringbits.actions.categories

import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.repositories.CategoriesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit
    ec: ExecutionContext
) {

  def apply(userId: UUID, request: CreateCategory.Request): Future[CreateCategory.Response] = {
    for {
      _ <- validateFormat(request)
      category <- categoriesRepository.getOrCreate(userId, request.name)
    } yield CreateCategory.Response(id = category.id, name = category.name)
  }

  private def validateFormat(request: CreateCategory.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 25) Future.failed(new RuntimeException("Name must be 25 characters or less"))
    else Future.unit
  }
}
