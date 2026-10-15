package net.wiringbits.config

import play.api.Configuration

case class S3Config(bucket: String, endpointOverride: Option[String])

object S3Config {
  def apply(config: Configuration): S3Config = {
    val bucket = config.get[String]("bucket")
    val endpointOverride = config.getOptional[String]("endpointOverride")
    S3Config(bucket, endpointOverride)
  }
}
