package net.wiringbits.modules

import com.google.inject.{AbstractModule, Provides}
import net.wiringbits.apis.{
  BdaClient,
  BedrockClient,
  MockBdaClient,
  MockBedrockClient,
  MockTextractClient,
  RealBdaClient,
  RealBedrockClient,
  RealTextractClient,
  TextractClient
}
import net.wiringbits.config.{AWSConfig, BdaConfig, BedrockConfig, TextractConfig}
import net.wiringbits.services.{BlobStorage, S3BlobStorage}
import org.apache.pekko.actor.ActorSystem

import javax.inject.Singleton
import scala.concurrent.ExecutionContext

class ServicesModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[BlobStorage]).to(classOf[S3BlobStorage]).asEagerSingleton()
  }

  @Provides
  @Singleton
  def textractClient(config: TextractConfig, awsConfig: AWSConfig, ec: ExecutionContext): TextractClient = {
    config.mode match {
      case TextractConfig.Mock => new MockTextractClient()
      case TextractConfig.Real => new RealTextractClient(awsConfig)(using ec)
    }
  }

  @Provides
  @Singleton
  def bedrockClient(config: BedrockConfig, ec: ExecutionContext): BedrockClient = {
    config.mode match {
      case BedrockConfig.Mock => new MockBedrockClient()
      case BedrockConfig.Real => new RealBedrockClient(config)(using ec)
    }
  }

  @Provides
  @Singleton
  def bdaClient(config: BdaConfig, awsConfig: AWSConfig, actorSystem: ActorSystem, ec: ExecutionContext): BdaClient = {
    config.mode match {
      case BdaConfig.Mock => new MockBdaClient()
      case BdaConfig.Real => new RealBdaClient(awsConfig, config, actorSystem)(using ec)
    }
  }
}
