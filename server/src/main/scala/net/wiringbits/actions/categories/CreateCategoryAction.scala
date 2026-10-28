package net.wiringbits.actions.categories

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.repositories.CategoriesRepository
import net.wiringbits.repositories.models.Category

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateCategoryAction @Inject() (categoriesRepository: CategoriesRepository)(implicit
    ec: ExecutionContext,
    clock: Clock
) {

  def apply(userId: UUID, request: CreateCategory.Request): Future[CreateCategory.Response] =
    for {
      _ <- validateFormat(request)
      now = clock.instant()
      create = request
        .into[Category.Create]
        .withFieldConst(_.id, UuidCreator.getTimeOrderedEpoch())
        .withFieldConst(_.userId, userId)
        .withFieldConst(_.createdAt, now)
        .withFieldConst(_.updatedAt, now)
        .transform
      category <- categoriesRepository.getOrCreate(create)
    } yield category.transformInto[CreateCategory.Response]

  private def validateFormat(request: CreateCategory.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 25) Future.failed(new RuntimeException("Name must be 25 characters or less"))
    else Future.unit
  }
}
