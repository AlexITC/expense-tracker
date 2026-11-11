package net.wiringbits.models

/** `entryName` (the case name) is the value stored in the `rate_limit_window.key` column. */
enum RateLimitKey extends DbEnum {
  case Bedrock
}

object RateLimitKey extends DbEnum.Companion[RateLimitKey]
