package net.wiringbits.validations

import net.wiringbits.repositories.CategoriesRepository

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

object ValidateCategoryNameIsAvailable {
  def apply(
      repository: CategoriesRepository,
      userId: UUID,
      name: String,
      excludeId: Option[UUID] = None
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      maybe <- repository.findByName(userId, name)
    } yield {
      val conflict = maybe.exists(c => !excludeId.contains(c.id))
      if (conflict) throw new RuntimeException("A category with that name already exists")
    }
  }
}
