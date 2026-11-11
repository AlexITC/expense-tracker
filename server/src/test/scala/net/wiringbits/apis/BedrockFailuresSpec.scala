package net.wiringbits.apis

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.services.bedrockruntime.model.{BedrockRuntimeException, ThrottlingException}

import java.util.concurrent.CompletionException

class BedrockFailuresSpec extends AnyWordSpec {

  "isThrottled" should {
    "detect a shared-limiter rejection" in {
      BedrockFailures.isThrottled(new BedrockRateLimitedException("limit reached")) must be(true)
    }

    "detect an AWS throttle wrapped in a CompletionException" in {
      val ex = new CompletionException(ThrottlingException.builder().message("slow down").statusCode(429).build())
      BedrockFailures.isThrottled(ex) must be(true)
    }

    "detect any AWS service error with status 429" in {
      BedrockFailures.isThrottled(
        BedrockRuntimeException.builder().message("too many").statusCode(429).build()
      ) must be(
        true
      )
    }

    "not treat other failures as throttles" in {
      BedrockFailures.isThrottled(BedrockRuntimeException.builder().message("bad").statusCode(400).build()) must be(
        false
      )
      BedrockFailures.isThrottled(new RuntimeException("no usable result")) must be(false)
    }
  }

  "isAwsSdkFailure" should {
    "detect an AWS SDK exception anywhere in the cause chain" in {
      val sdk = BedrockRuntimeException.builder().message("bad").statusCode(400).build()
      BedrockFailures.isAwsSdkFailure(new RuntimeException("outer", new CompletionException(sdk))) must be(true)
    }

    "ignore non-SDK failures" in {
      BedrockFailures.isAwsSdkFailure(new RuntimeException("no usable result")) must be(false)
    }
  }
}
