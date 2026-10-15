package net.wiringbits.apis

import net.wiringbits.config.{AWSConfig, BdaConfig}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import play.api.libs.json.{JsValue, Json}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.bedrockdataautomationruntime.BedrockDataAutomationRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockdataautomationruntime.model.{
  Blueprint,
  DataAutomationConfiguration,
  GetDataAutomationStatusRequest,
  InputConfiguration,
  InvokeDataAutomationAsyncRequest,
  OutputConfiguration
}
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{CopyObjectRequest, DeleteObjectRequest, GetObjectRequest}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

class RealBdaClient(awsConfig: AWSConfig, bdaConfig: BdaConfig, actorSystem: ActorSystem)(implicit ec: ExecutionContext)
    extends BdaClient {

  private val credentials =
    StaticCredentialsProvider.create(
      AwsBasicCredentials.create(awsConfig.accessKeyId.string, awsConfig.secretAccessKey.string)
    )
  private val runtime: BedrockDataAutomationRuntimeAsyncClient =
    BedrockDataAutomationRuntimeAsyncClient.builder().region(awsConfig.region).credentialsProvider(credentials).build()
  private val s3: S3AsyncClient =
    S3AsyncClient.builder().region(awsConfig.region).credentialsProvider(credentials).build()

  private val PollInterval = 5.seconds
  private val MaxPolls = 60

  override def standardMarkdown(storageKey: String): Future[Option[String]] =
    invokeAndFetch(storageKey, custom = false).map(_.flatMap(extractMarkdown))

  override def customExtraction(storageKey: String): Future[Option[JsValue]] =
    invokeAndFetch(storageKey, custom = true)

  private def invokeAndFetch(storageKey: String, custom: Boolean): Future[Option[JsValue]] =
    // BDA infers the input format from the key's extension; our blobs are stored extensionless
    // (data/<user>/<date>/<sha256>, content-type application/octet-stream), which BDA rejects with
    // "format of the input file isn't supported". So run it against a temporary .pdf-suffixed copy.
    withPdfCopy(storageKey) { inputKey =>
      val inputUri = s"s3://${awsConfig.s3.bucket}/$inputKey"
      val base = InvokeDataAutomationAsyncRequest
        .builder()
        .inputConfiguration(InputConfiguration.builder().s3Uri(inputUri).build())
        .outputConfiguration(OutputConfiguration.builder().s3Uri(bdaConfig.outputS3Uri).build())
        .dataAutomationProfileArn(bdaConfig.profileArn)
      val request =
        if (custom)
          base
            .blueprints(Blueprint.builder().blueprintArn(bdaConfig.bankStatementBlueprintArn).stage("LIVE").build())
            .build()
        else
          base
            .dataAutomationConfiguration(
              DataAutomationConfiguration
                .builder()
                .dataAutomationProjectArn(bdaConfig.standardProjectArn)
                .stage("LIVE")
                .build()
            )
            .build()
      runtime.invokeDataAutomationAsync(request).asScala.flatMap(resp => poll(resp.invocationArn(), 0))
    }

  /** Copy the blob to a temporary `.pdf` key (so BDA recognises the format), run `f` against it, then delete the copy.
    */
  private def withPdfCopy[T](storageKey: String)(f: String => Future[T]): Future[T] = {
    val bucket = awsConfig.s3.bucket
    val pdfKey = s"$storageKey.pdf"
    s3.copyObject(
      CopyObjectRequest
        .builder()
        .sourceBucket(bucket)
        .sourceKey(storageKey)
        .destinationBucket(bucket)
        .destinationKey(pdfKey)
        .build()
    ).asScala
      .flatMap(_ => f(pdfKey))
      .andThen { case _ =>
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(pdfKey).build())
      }
  }

  private def poll(invocationArn: String, polls: Int): Future[Option[JsValue]] =
    runtime
      .getDataAutomationStatus(GetDataAutomationStatusRequest.builder().invocationArn(invocationArn).build())
      .asScala
      .flatMap { st =>
        st.statusAsString() match {
          case "Success" => readResult(st.outputConfiguration().s3Uri())
          case "InProgress" | "Created" if polls < MaxPolls =>
            after(PollInterval, actorSystem.scheduler)(poll(invocationArn, polls + 1))
          case _ => Future.successful(None)
        }
      }

  /** The output s3Uri points to job_metadata.json; follow it to the single segment's result.json. */
  private def readResult(jobMetadataUri: String): Future[Option[JsValue]] =
    readS3Json(jobMetadataUri).flatMap {
      case None => Future.successful(None)
      case Some(meta) =>
        val resultPath = (meta \ "output_metadata")
          .asOpt[Seq[JsValue]]
          .getOrElse(Nil)
          .flatMap(seg => (seg \ "segment_metadata").asOpt[Seq[JsValue]].getOrElse(Nil))
          .flatMap(m => (m \ "custom_output_path").asOpt[String].orElse((m \ "standard_output_path").asOpt[String]))
          .headOption
        resultPath.fold(Future.successful(Option.empty[JsValue]))(readS3Json)
    }

  private def readS3Json(s3Uri: String): Future[Option[JsValue]] = {
    val rest = s3Uri.stripPrefix("s3://")
    val slash = rest.indexOf('/')
    val (bucket, key) = (rest.substring(0, slash), rest.substring(slash + 1))
    s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), AsyncResponseTransformer.toBytes())
      .asScala
      .map(bytes => Option(Json.parse(bytes.asByteArray())))
      .recover { case _ => None }
  }

  private def extractMarkdown(result: JsValue): Option[String] =
    (result \ "document" \ "representation" \ "markdown")
      .asOpt[String]
      .orElse {
        val pages = (result \ "pages")
          .asOpt[Seq[JsValue]]
          .getOrElse(Nil)
          .flatMap(p => (p \ "representation" \ "markdown").asOpt[String])
        Option.when(pages.nonEmpty)(pages.mkString("\n\n"))
      }
      .filter(_.trim.nonEmpty)
}
