package net.wiringbits.actions.savings

import net.wiringbits.api.models.savings.GetSavingsAllocations
import net.wiringbits.repositories.SavingsRepository
import net.wiringbits.repositories.models.SavingsAllocation

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetSavingsAllocationsAction @Inject() (savingsRepository: SavingsRepository)(implicit ec: ExecutionContext) {

  def apply(goalId: UUID, userId: UUID): Future[GetSavingsAllocations.Response] = {
    for {
      existing <- savingsRepository.findGoal(goalId, userId)
      _ = if (existing.isEmpty) throw new RuntimeException("Savings goal not found")
      allocations <- savingsRepository.allAllocations(goalId, userId)
    } yield GetSavingsAllocations.Response(allocations.map(toAllocation))
  }

  private def toAllocation(a: SavingsAllocation): GetSavingsAllocations.Response.Allocation =
    GetSavingsAllocations.Response.Allocation(a.id, a.amount, a.date, a.note, a.merchantId, a.createdAt)
}
