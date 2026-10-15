package net.wiringbits.actions.savings

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.savings.CreateSavingsAllocation
import net.wiringbits.repositories.SavingsRepository
import net.wiringbits.repositories.models.SavingsAllocation

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateSavingsAllocationAction @Inject() (savingsRepository: SavingsRepository)(implicit
    ec: ExecutionContext,
    clock: Clock
) {

  def apply(
      goalId: UUID,
      userId: UUID,
      request: CreateSavingsAllocation.Request
  ): Future[CreateSavingsAllocation.Response] = {
    for {
      _ <- validate(goalId, userId, request)
      now = clock.instant()
      id = UuidCreator.getTimeOrderedEpoch()
      create = SavingsAllocation.Create(
        id,
        userId,
        goalId,
        request.amount,
        request.date,
        request.note,
        request.merchantId,
        now,
        now
      )
      _ <- savingsRepository.createAllocation(create)
    } yield CreateSavingsAllocation.Response(id)
  }

  private def validate(goalId: UUID, userId: UUID, request: CreateSavingsAllocation.Request): Future[Unit] = {
    if (request.amount == 0) Future.failed(new RuntimeException("Amount must not be zero"))
    else
      for {
        existing <- savingsRepository.findGoal(goalId, userId)
        _ = if (existing.isEmpty) throw new RuntimeException("Savings goal not found")
      } yield ()
  }
}
