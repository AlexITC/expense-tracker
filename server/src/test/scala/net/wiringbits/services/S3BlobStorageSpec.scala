package net.wiringbits.services

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import net.wiringbits.config.{AWSConfig, S3Config}
import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, ListObjectsV2Request}

import java.net.URI
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class S3BlobStorageSpec extends AnyWordSpec with ForAllTestContainer {

  implicit val patienceConfig: PatienceConfig = PatienceConfig(30.seconds, 500.milliseconds)
  implicit val ec: ExecutionContext = ExecutionContext.global

  private val testBucket = "test-documents"
  private val s3MockPort = 9090

  override val container: GenericContainer = GenericContainer(
    dockerImage = "adobe/s3mock:latest",
    exposedPorts = Seq(s3MockPort),
    waitStrategy = Wait.forHttp("/").forPort(s3MockPort)
  )

  private lazy val endpoint: URI =
    URI.create(s"http://${container.host}:${container.mappedPort(s3MockPort)}")

  private lazy val syncClient: S3Client = {
    val creds = AwsBasicCredentials.create("test", "test")
    val c = S3Client
      .builder()
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(creds))
      .endpointOverride(endpoint)
      .forcePathStyle(true)
      .build()
    c.createBucket(CreateBucketRequest.builder().bucket(testBucket).build())
    c
  }

  private lazy val storage: S3BlobStorage = {
    val awsConfig = AWSConfig(
      accessKeyId = AWSAccessKeyId("test"),
      secretAccessKey = AWSSecretAccessKey("test"),
      region = Region.US_EAST_1,
      s3 = S3Config(bucket = testBucket, endpointOverride = Some(endpoint.toString))
    )
    new S3BlobStorage(awsConfig)
  }

  private def cleanBucket(): Unit = {
    val listed = syncClient.listObjectsV2(ListObjectsV2Request.builder().bucket(testBucket).build())
    listed.contents().forEach { obj =>
      syncClient.deleteObject(
        software.amazon.awssdk.services.s3.model.DeleteObjectRequest
          .builder()
          .bucket(testBucket)
          .key(obj.key())
          .build()
      )
    }
  }

  "put and get" should {
    "store bytes and retrieve them at the given key" in {
      cleanBucket()
      val key = "data/user1/2024-01-15/abc123"
      val bytes = "hello s3".getBytes("UTF-8")

      storage.put(key, bytes).futureValue
      val result = storage.get(key).futureValue

      result must not be empty
      new String(result.get, "UTF-8") must be("hello s3")
    }

    "return None for a key that does not exist" in {
      cleanBucket()
      storage.get("data/user1/2024-01-15/nonexistent").futureValue must be(None)
    }
  }

  "delete" should {
    "remove the object from S3" in {
      cleanBucket()
      val key = "data/user1/2024-01-15/todelete"
      storage.put(key, "content".getBytes("UTF-8")).futureValue

      storage.delete(key).futureValue
      storage.get(key).futureValue must be(None)
    }

    "succeed even if the object does not exist (idempotent)" in {
      cleanBucket()
      storage.delete("data/user1/2024-01-15/already-gone").futureValue
    }
  }

  "key format" should {
    "store object at the exact key data/<userId>/<date>/<sha256>" in {
      cleanBucket()
      val userId = "7c7f1234-0000-0000-0000-000000000001"
      val date = "2024-03-20"
      val sha256 = "deadbeef"
      val expectedKey = s"data/$userId/$date/$sha256"

      storage.put(expectedKey, Array[Byte](1, 2, 3)).futureValue

      val listed = syncClient.listObjectsV2(ListObjectsV2Request.builder().bucket(testBucket).build())
      val keys = listed.contents().stream().map(_.key()).toList
      keys must contain(expectedKey)
    }
  }
}
