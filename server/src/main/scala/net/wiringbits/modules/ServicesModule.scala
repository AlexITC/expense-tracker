package net.wiringbits.modules

import com.google.inject.{AbstractModule, Provides}
import net.wiringbits.apis.{
  BdaClient,
  BedrockClient,
  JevClient,
  MockBdaClient,
  MockBedrockClient,
  MockTextractClient,
  RealBdaClient,
  RealBedrockClient,
  RealTextractClient,
  TextractClient
}
import net.wiringbits.config.{AWSConfig, BdaConfig, BedrockConfig, JevConfig, TextractConfig}
import net.wiringbits.services.{BlobStorage, DbRateLimiter, S3BlobStorage}
import net.wiringbits.util.{BcryptPasswordHasher, PasswordHasher, RateLimiter}
import org.apache.pekko.actor.ActorSystem
import play.api.libs.ws.WSClient

import javax.inject.Singleton
import scala.concurrent.ExecutionContext

class ServicesModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[BlobStorage]).to(classOf[S3BlobStorage]).asEagerSingleton()
    bind(classOf[RateLimiter]).to(classOf[DbRateLimiter])
    // Default bcrypt cost. Tests override this binding with a lower cost to keep real hashing cheap.
    bind(classOf[PasswordHasher]).toInstance(new BcryptPasswordHasher(logRounds = 10))
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
  def bedrockClient(config: BedrockConfig, rateLimiter: RateLimiter, ec: ExecutionContext): BedrockClient = {
    config.mode match {
      case BedrockConfig.Mock => new MockBedrockClient()
      case BedrockConfig.Real =>
        new RealBedrockClient(config, rateLimiter, RealBedrockClient.awsClient(config))(using ec)
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

  @Provides
  @Singleton
  def jevClient(config: JevConfig, ws: WSClient, actorSystem: ActorSystem, ec: ExecutionContext): JevClient = {
    config.mode match {
      case JevConfig.Mock => new JevClient.MockJevClient()
      case JevConfig.Real => new JevClient.RealJevClient(config, ws, actorSystem)(using ec)
    }
  }
}
