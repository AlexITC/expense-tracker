package net.wiringbits.config

import play.api.Configuration

// reconciliationToleranceDays: how many days a charge may land before or after a due date and still pay it.
case class SubscriptionsConfig(maxPerUser: Int, reconciliationToleranceDays: Int)

object SubscriptionsConfig {
  def apply(config: Configuration): SubscriptionsConfig = {
    SubscriptionsConfig(
      maxPerUser = config.get[Int]("maxPerUser"),
      reconciliationToleranceDays = config.get[Int]("reconciliationToleranceDays")
    )
  }
}
