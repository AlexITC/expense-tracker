package net.wiringbits.apis

import net.wiringbits.config.BedrockConfig
import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey, RateLimitKey}
import net.wiringbits.util.RateLimiter
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseOutput,
  ConverseRequest,
  ConverseResponse,
  Message,
  StopReason
}

import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

class RealBedrockClientSpec extends AnyWordSpec with ScalaFutures {

  private val config = BedrockConfig(
    accessKeyId = AWSAccessKeyId("test"),
    secretAccessKey = AWSSecretAccessKey("test"),
    region = Region.US_EAST_1,
    modelId = "test-model",
    statementModelId = "test-statement-model",
    statementMaxTokens = 1000,
    suggestionsModelId = "test-suggestions-model",
    requestsPerMinute = 7,
    mode = BedrockConfig.Real
  )

  private class FakeAwsClient(text: String) extends BedrockRuntimeAsyncClient {
    val calls = new AtomicInteger(0)
    override def serviceName(): String = "bedrock-runtime-fake"
    override def close(): Unit = ()
    override def converse(request: ConverseRequest): CompletableFuture[ConverseResponse] = {
      calls.incrementAndGet()
      val message = Message.builder().role(ConversationRole.ASSISTANT).content(ContentBlock.fromText(text)).build()
      CompletableFuture.completedFuture(
        ConverseResponse
          .builder()
          .output(ConverseOutput.builder().message(message).build())
          .stopReason(StopReason.END_TURN)
          .build()
      )
    }
  }

  private class StubRateLimiter(granted: Boolean) extends RateLimiter {
    val requests = new ConcurrentLinkedQueue[(RateLimitKey, Int)]()
    override def tryAcquire(key: RateLimitKey, limit: Int): Future[Boolean] = {
      requests.add((key, limit))
      Future.successful(granted)
    }
  }

  "extractBankStatement" should {
    "fail with BedrockRateLimitedException without calling AWS when the limiter denies the slot" in {
      val aws = new FakeAwsClient("{}")
      val client = new RealBedrockClient(config, new StubRateLimiter(false), aws)

      val error = client.extractBankStatement("synthetic statement", None).failed.futureValue

      error mustBe a[BedrockRateLimitedException]
      aws.calls.get() mustBe 0
    }

    "return the model text when the limiter grants the slot" in {
      val aws = new FakeAwsClient("""{"transactions":[]}""")
      val client = new RealBedrockClient(config, new StubRateLimiter(true), aws)

      client.extractBankStatement("synthetic statement", None).futureValue mustBe """{"transactions":[]}"""
      aws.calls.get() mustBe 1
    }

    "ask the limiter for the Bedrock key with the configured requests per minute" in {
      val limiter = new StubRateLimiter(true)
      val client = new RealBedrockClient(config, limiter, new FakeAwsClient("ok"))

      client.extractBankStatement("synthetic statement", None).futureValue

      limiter.requests.asScala.toList mustBe List((RateLimitKey.Bedrock, 7))
    }
  }

  "suggest" should {
    "fail with BedrockRateLimitedException without calling AWS when the limiter denies the slot" in {
      val aws = new FakeAwsClient("ok")
      val client = new RealBedrockClient(config, new StubRateLimiter(false), aws)

      val error = client.suggest("synthetic prompt").failed.futureValue

      error mustBe a[BedrockRateLimitedException]
      aws.calls.get() mustBe 0
    }

    "return the model text when the limiter grants the slot" in {
      val aws = new FakeAwsClient("suggestion")
      val client = new RealBedrockClient(config, new StubRateLimiter(true), aws)

      client.suggest("synthetic prompt").futureValue mustBe "suggestion"
      aws.calls.get() mustBe 1
    }

    "ask the limiter for the Bedrock key with the configured requests per minute" in {
      val limiter = new StubRateLimiter(true)
      val client = new RealBedrockClient(config, limiter, new FakeAwsClient("ok"))

      client.suggest("synthetic prompt").futureValue

      limiter.requests.asScala.toList mustBe List((RateLimitKey.Bedrock, 7))
    }
  }
}
