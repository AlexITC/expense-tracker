package net.wiringbits.api.models.bankstatements

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

/** The authenticated user's account nicknames. The frontend fetches these once and maps each one onto the statements of
  * the matching account (bank + product + last4 + currency); only named accounts are returned.
  */
object GetAccountAliases {

  case class Account(
      bankName: String,
      productType: String,
      accountLast4: String,
      currency: String,
      alias: String
  )
  case class Response(accounts: List[Account])

  implicit val accountFormat: Format[Account] = Json.format[Account]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val accountSchema: Schema[Account] =
    Schema.derived[Account].name(Schema.SName("AccountAlias"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetAccountAliasesResponse"))
}
