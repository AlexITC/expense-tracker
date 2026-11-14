package net.wiringbits.config

import play.api.Configuration

case class SubscriptionsConfig(maxPerUser: Int)

object SubscriptionsConfig {
  def apply(config: Configuration): SubscriptionsConfig = {
    SubscriptionsConfig(config.get[Int]("maxPerUser"))
  }
}
