package net.wiringbits.repositories.models

import java.util.UUID

case class ExpenseCategorySplit(
    id: UUID,
    userId: UUID,
    expenseId: UUID,
    categoryId: UUID,
    amount: BigDecimal
)

object ExpenseCategorySplit {
  case class Create(
      id: UUID,
      userId: UUID,
      expenseId: UUID,
      categoryId: UUID,
      amount: BigDecimal
  )
}
