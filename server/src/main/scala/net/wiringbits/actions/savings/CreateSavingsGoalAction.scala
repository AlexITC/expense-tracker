package net.wiringbits.actions.savings

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.savings.CreateSavingsGoal
import net.wiringbits.repositories.SavingsRepository
import net.wiringbits.repositories.models.SavingsGoal

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateSavingsGoalAction @Inject() (savingsRepository: SavingsRepository)(implicit
    ec: ExecutionContext,
    clock: Clock
) {

  def apply(userId: UUID, request: CreateSavingsGoal.Request): Future[CreateSavingsGoal.Response] = {
    for {
      _ <- validate(userId, request)
      now = clock.instant()
      id = UuidCreator.getTimeOrderedEpoch()
      create = SavingsGoal.Create(id, userId, request.name, request.targetAmount, now, now)
      _ <- savingsRepository.createGoal(create)
    } yield CreateSavingsGoal.Response(id)
  }

  private def validate(userId: UUID, request: CreateSavingsGoal.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 100) Future.failed(new RuntimeException("Name must be 100 characters or less"))
    else if (request.targetAmount.exists(_ <= 0))
      Future.failed(new RuntimeException("Target amount must be greater than zero"))
    else Future.unit
  }
}
