package net.wiringbits.config

import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import play.api.Configuration
import software.amazon.awssdk.regions.Region

case class BedrockConfig(
    accessKeyId: AWSAccessKeyId,
    secretAccessKey: AWSSecretAccessKey,
    region: Region,
    modelId: String,
    statementModelId: String,
    statementMaxTokens: Int,
    suggestionsModelId: String,
    mode: BedrockConfig.Mode
) {
  override def toString: String =
    s"BedrockConfig(region = $region, accessKeyId = ${accessKeyId.toString}, secretAccessKey = ${secretAccessKey.toString}, modelId = $modelId, suggestionsModelId = $suggestionsModelId, mode = $mode)"
}

object BedrockConfig {
  sealed trait Mode
  case object Mock extends Mode
  case object Real extends Mode

  def apply(config: Configuration): BedrockConfig = {
    val accessKeyId = config.get[String]("accessKeyId")
    val secretAccessKey = config.get[String]("secretAccessKey")
    val region = config.get[String]("region")
    val modelId = config.get[String]("modelId")
    val statementModelId = config.get[String]("statementModelId")
    val statementMaxTokens = config.get[Int]("statementMaxTokens")
    val suggestionsModelId = config.get[String]("suggestionsModelId")
    val mode = config.get[String]("mode") match {
      case "mock" => Mock
      case "real" => Real
      case other => throw new IllegalArgumentException(s"Unknown bedrock.mode '$other'; expected 'mock' or 'real'")
    }
    BedrockConfig(
      AWSAccessKeyId(accessKeyId),
      AWSSecretAccessKey(secretAccessKey),
      Region.of(region),
      modelId,
      statementModelId,
      statementMaxTokens,
      suggestionsModelId,
      mode
    )
  }
}
