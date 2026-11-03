package net.wiringbits.common

object ExpenseSplits {
  val NotFullyAllocatedError = "Split amounts must add up to the expense amount"

  /** An expense's splits must cover its amount exactly: no unallocated remainder and no over-allocation. */
  def isFullyAllocated(amount: BigDecimal, splits: List[BigDecimal]): Boolean =
    splits.sum == amount
}
