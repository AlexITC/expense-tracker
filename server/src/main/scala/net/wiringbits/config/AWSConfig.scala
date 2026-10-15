package net.wiringbits.config

import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import play.api.Configuration
import software.amazon.awssdk.regions.Region

case class AWSConfig(accessKeyId: AWSAccessKeyId, secretAccessKey: AWSSecretAccessKey, region: Region, s3: S3Config) {
  override def toString: String =
    s"AwsConfig(region = $region, accessKeyId = ${accessKeyId.toString}, secretAccessKey = ${secretAccessKey.toString}, s3.bucket = ${s3.bucket})"
}

object AWSConfig {
  def apply(config: Configuration): AWSConfig = {
    val accessKeyId = config.get[String]("accessKeyId")
    val secretAccessKey = config.get[String]("secretAccessKey")
    val region = config.get[String]("region")
    val s3 = S3Config(config.get[Configuration]("s3"))

    AWSConfig(AWSAccessKeyId(accessKeyId), AWSSecretAccessKey(secretAccessKey), Region.of(region), s3)
  }
}
