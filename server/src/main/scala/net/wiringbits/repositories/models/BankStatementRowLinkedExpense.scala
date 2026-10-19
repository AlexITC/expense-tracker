package net.wiringbits.repositories.models

import java.util.UUID

case class BankStatementRowLinkedExpense(rowId: UUID, merchantId: Option[UUID], categoryId: Option[UUID])
