package net.wiringbits.config

import play.api.Configuration

case class MerchantsConfig(maxPerUser: Int)

object MerchantsConfig {
  def apply(config: Configuration): MerchantsConfig = {
    MerchantsConfig(config.get[Int]("maxPerUser"))
  }
}
