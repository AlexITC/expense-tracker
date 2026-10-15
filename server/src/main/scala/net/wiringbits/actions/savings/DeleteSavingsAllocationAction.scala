package net.wiringbits.actions.savings

import net.wiringbits.api.models.savings.DeleteSavingsAllocation
import net.wiringbits.repositories.SavingsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteSavingsAllocationAction @Inject() (savingsRepository: SavingsRepository)(implicit ec: ExecutionContext) {

  def apply(goalId: UUID, allocationId: UUID, userId: UUID): Future[DeleteSavingsAllocation.Response] = {
    for {
      existing <- savingsRepository.findAllocation(allocationId, goalId, userId)
      _ = if (existing.isEmpty) throw new RuntimeException("Savings allocation not found")
      _ <- savingsRepository.deleteAllocation(allocationId, goalId, userId)
    } yield DeleteSavingsAllocation.Response()
  }
}
