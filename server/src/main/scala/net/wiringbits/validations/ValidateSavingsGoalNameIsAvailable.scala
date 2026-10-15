package net.wiringbits.validations

import net.wiringbits.repositories.SavingsRepository

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

object ValidateSavingsGoalNameIsAvailable {
  def apply(
      repository: SavingsRepository,
      userId: UUID,
      name: String,
      excludeId: Option[UUID] = None
  )(implicit ec: ExecutionContext): Future[Unit] =
    repository.findGoalByName(userId, name).map { maybe =>
      val conflict = maybe.exists(g => !excludeId.contains(g.id))
      if (conflict) throw new RuntimeException("A savings goal with that name already exists")
    }
}
