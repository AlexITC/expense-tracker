package net.wiringbits.services

import net.wiringbits.config.AWSConfig
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  GetObjectRequest,
  HeadObjectRequest,
  NoSuchKeyException,
  PutObjectRequest
}

import java.net.URI
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps

class S3BlobStorage @Inject() (awsConfig: AWSConfig)(implicit ec: ExecutionContext) extends BlobStorage {

  private val s3Config = awsConfig.s3

  private val client: S3AsyncClient = {
    val credentials = AwsBasicCredentials.create(awsConfig.accessKeyId.string, awsConfig.secretAccessKey.string)
    val builder = S3AsyncClient
      .builder()
      .region(awsConfig.region)
      .credentialsProvider(StaticCredentialsProvider.create(credentials))

    s3Config.endpointOverride.foreach { endpoint =>
      builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true)
    }

    builder.build()
  }

  override def put(storageKey: String, bytes: Array[Byte]): Future[Unit] = {
    val request = PutObjectRequest.builder().bucket(s3Config.bucket).key(storageKey).build()
    client.putObject(request, AsyncRequestBody.fromBytes(bytes)).asScala.map(_ => ())
  }

  override def exists(storageKey: String): Future[Boolean] = {
    val request = HeadObjectRequest.builder().bucket(s3Config.bucket).key(storageKey).build()
    client
      .headObject(request)
      .asScala
      .map(_ => true)
      .recover {
        case _: NoSuchKeyException => false
        case ex: java.util.concurrent.CompletionException =>
          ex.getCause match {
            case _: NoSuchKeyException => false
            case c => throw c
          }
      }
  }

  override def get(storageKey: String): Future[Option[Array[Byte]]] = {
    val request = GetObjectRequest.builder().bucket(s3Config.bucket).key(storageKey).build()
    client
      .getObject(request, AsyncResponseTransformer.toBytes())
      .asScala
      .map(r => Some(r.asByteArrayUnsafe()))
      .recover {
        case _: NoSuchKeyException => None
        case ex: java.util.concurrent.CompletionException =>
          ex.getCause match {
            case _: NoSuchKeyException => None
            case c => throw c
          }
      }
  }

  override def delete(storageKey: String): Future[Unit] = {
    val request = DeleteObjectRequest.builder().bucket(s3Config.bucket).key(storageKey).build()
    client
      .deleteObject(request)
      .asScala
      .map(_ => ())
      .recover {
        case _: NoSuchKeyException => ()
        case ex: java.util.concurrent.CompletionException =>
          ex.getCause match {
            case _: NoSuchKeyException => ()
            case c => throw c
          }
      }
  }
}
