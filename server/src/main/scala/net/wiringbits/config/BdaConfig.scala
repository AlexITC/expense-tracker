package net.wiringbits.config

import play.api.Configuration

/** Amazon Bedrock Data Automation config. `standardProjectArn` is a project configured for standard (markdown) document
  * output; `bankStatementBlueprintArn` is the managed/custom blueprint for structured custom output; `outputS3Uri` is
  * where BDA writes results; `profileArn` is the data-automation profile.
  */
case class BdaConfig(
    mode: BdaConfig.Mode,
    profileArn: String,
    standardProjectArn: String,
    bankStatementBlueprintArn: String,
    outputS3Uri: String
)

object BdaConfig {
  sealed trait Mode
  case object Mock extends Mode
  case object Real extends Mode

  def apply(config: Configuration): BdaConfig = {
    val mode = config.get[String]("mode") match {
      case "mock" => Mock
      case "real" => Real
      case other => throw new IllegalArgumentException(s"Unknown bda.mode '$other'; expected 'mock' or 'real'")
    }
    BdaConfig(
      mode = mode,
      profileArn = config.get[String]("profileArn"),
      standardProjectArn = config.get[String]("standardProjectArn"),
      bankStatementBlueprintArn = config.get[String]("bankStatementBlueprintArn"),
      outputS3Uri = config.get[String]("outputS3Uri")
    )
  }
}
