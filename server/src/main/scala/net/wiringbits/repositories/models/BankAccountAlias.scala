package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class BankAccountAlias(
    id: UUID,
    userId: UUID,
    bankName: String,
    productType: String,
    accountLast4: String,
    currency: String,
    alias: String,
    createdAt: Instant,
    updatedAt: Instant
)

object BankAccountAlias {

  /** Identifies an account within a user's statements; build it from statement metadata with Chimney's
    * `transformInto[BankAccountAlias.Key]`.
    */
  case class Key(bankName: String, productType: String, accountLast4: String, currency: String)
}
