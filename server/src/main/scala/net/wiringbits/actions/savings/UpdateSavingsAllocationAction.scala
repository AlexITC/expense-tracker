package net.wiringbits.actions.savings

import net.wiringbits.api.models.savings.UpdateSavingsAllocation
import net.wiringbits.repositories.SavingsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UpdateSavingsAllocationAction @Inject() (savingsRepository: SavingsRepository)(implicit ec: ExecutionContext) {

  def apply(
      goalId: UUID,
      allocationId: UUID,
      userId: UUID,
      request: UpdateSavingsAllocation.Request
  ): Future[UpdateSavingsAllocation.Response] = {
    for {
      _ <- validate(goalId, allocationId, userId, request)
      _ <- savingsRepository.updateAllocation(
        allocationId,
        goalId,
        userId,
        request.amount,
        request.date,
        request.note,
        request.merchantId
      )
    } yield UpdateSavingsAllocation.Response()
  }

  private def validate(
      goalId: UUID,
      allocationId: UUID,
      userId: UUID,
      request: UpdateSavingsAllocation.Request
  ): Future[Unit] = {
    if (request.amount == 0) Future.failed(new RuntimeException("Amount must not be zero"))
    else
      for {
        existing <- savingsRepository.findAllocation(allocationId, goalId, userId)
        _ = if (existing.isEmpty) throw new RuntimeException("Savings allocation not found")
      } yield ()
  }
}
