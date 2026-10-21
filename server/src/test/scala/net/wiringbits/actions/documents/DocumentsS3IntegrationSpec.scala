package net.wiringbits.actions.documents

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer, MultipleContainers, PostgreSQLContainer}
import net.wiringbits.config.{AWSConfig, S3Config}
import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.*
import net.wiringbits.repositories.models.{BackgroundJobData, Document}
import net.wiringbits.services.{BlobStorage, S3BlobStorage}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import play.api.db.evolutions.Evolutions
import play.api.db.{Database, Databases}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteObjectRequest, ListObjectsV2Request}
import utils.Executors

import java.net.URI
import java.sql.DriverManager
import java.time.{Clock, Instant}
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class DocumentsS3IntegrationSpec extends AnyWordSpec with ForAllTestContainer {

  implicit val patienceConfig: PatienceConfig = PatienceConfig(60.seconds, 500.milliseconds)
  implicit val ec: ExecutionContext = ExecutionContext.global

  private val testBucket = "test-documents-integration"
  private val s3MockPort = 9090
  private val postgresImage = DockerImageName.parse("postgres:13")

  private val postgres = PostgreSQLContainer.Def(dockerImageName = postgresImage).createContainer()
  private val s3mock = GenericContainer(
    dockerImage = "adobe/s3mock:latest",
    exposedPorts = Seq(s3MockPort),
    waitStrategy = Wait.forHttp("/").forPort(s3MockPort)
  )

  override val container: MultipleContainers = MultipleContainers(postgres, s3mock)

  private lazy val db: Database = {
    val conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    conn.createStatement().execute("CREATE EXTENSION CITEXT;")
    conn.close()
    val d = Databases(
      driver = "org.postgresql.Driver",
      url = postgres.jdbcUrl,
      name = "default",
      config = Map("username" -> postgres.username, "password" -> postgres.password)
    )
    Evolutions.applyEvolutions(d)
    d
  }

  private lazy val endpoint: URI =
    URI.create(s"http://${s3mock.host}:${s3mock.mappedPort(s3MockPort)}")

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

  private lazy val storage: BlobStorage = {
    val awsConfig = AWSConfig(
      accessKeyId = AWSAccessKeyId("test"),
      secretAccessKey = AWSSecretAccessKey("test"),
      region = Region.US_EAST_1,
      s3 = S3Config(bucket = testBucket, endpointOverride = Some(endpoint.toString))
    )
    new S3BlobStorage(awsConfig)
  }

  private lazy val documentsRepo: DocumentsRepository =
    new DocumentsRepository(db)(using Executors.databaseEC)

  override def beforeStop(): Unit = {
    db.shutdown()
    super.beforeStop()
  }

  private def cleanAll(): Unit = {
    val listed = syncClient.listObjectsV2(ListObjectsV2Request.builder().bucket(testBucket).build())
    listed.contents().forEach { obj =>
      syncClient.deleteObject(DeleteObjectRequest.builder().bucket(testBucket).key(obj.key()).build())
    }
    db.withConnection { conn =>
      val stmt = conn.createStatement()
      stmt.execute("DELETE FROM background_jobs")
      stmt.execute("DELETE FROM document")
      stmt.execute("DELETE FROM users")
    }
  }

  private def makeUser(): UUID = {
    val id = UUID.randomUUID()
    db.withConnection { conn =>
      conn
        .createStatement()
        .execute(
          s"INSERT INTO users (user_id, name, email, password, created_at, verified_on) " +
            s"VALUES ('$id', 'Test', 'test_${id}@example.com', 'hash', now(), now())"
        )
    }
    id
  }

  private def makeDocument(userId: UUID, sha256: String, storageKey: String): Document.Create = {
    val now = Instant.now()
    val create = Document.Create(
      id = UUID.randomUUID(),
      userId = userId,
      sha256 = sha256,
      storageKey = storageKey,
      kind = DocumentKind.Receipt,
      filename = "test.pdf",
      createdAt = now,
      updatedAt = now
    )
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ProcessDocument,
      payload = BackgroundJobPayload.ProcessDocument(create.id),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
    documentsRepo.create(create, job).futureValue
    create
  }

  private def countS3Objects(): Int =
    syncClient.listObjectsV2(ListObjectsV2Request.builder().bucket(testBucket).build()).contents().size()

  "upload" should {
    "PUT object at exact key data/<userId>/<date>/<sha256> and create document row synchronously" in {
      cleanAll()
      val userId = makeUser()
      val bytes = "receipt-bytes".getBytes("UTF-8")
      val action = new UploadDocumentAction(documentsRepo, storage)(using ec, Clock.systemUTC())

      val resp = action(userId, bytes, "receipt", "r.pdf").futureValue

      val doc = documentsRepo.find(resp.id, userId).futureValue.get
      doc.storageKey must startWith(s"data/$userId/")
      doc.storageKey must endWith(doc.sha256)

      val retrieved = storage.get(doc.storageKey).futureValue
      retrieved must not be empty
      new String(retrieved.get, "UTF-8") must be("receipt-bytes")
    }

    "reject duplicate upload (same user, same bytes) without issuing a second S3 put" in {
      cleanAll()
      val userId = makeUser()
      val bytes = "dup-bytes".getBytes("UTF-8")
      val action = new UploadDocumentAction(documentsRepo, storage)(using ec, Clock.systemUTC())

      action(userId, bytes, "receipt", "a.pdf").futureValue
      val objectsAfterFirst = countS3Objects()

      action(userId, bytes, "receipt", "b.pdf").futureValue
      countS3Objects() must be(objectsAfterFirst)
    }

    "fail cleanly when S3 put fails — no document row is created" in {
      cleanAll()
      val userId = makeUser()
      val bytes = "fail-bytes".getBytes("UTF-8")
      val badConfig = AWSConfig(
        accessKeyId = AWSAccessKeyId("test"),
        secretAccessKey = AWSSecretAccessKey("test"),
        region = Region.US_EAST_1,
        s3 = S3Config(
          bucket = "nonexistent-bucket",
          endpointOverride = Some(s"http://${s3mock.host}:${s3mock.mappedPort(s3MockPort)}")
        )
      )
      val brokenStorage = new S3BlobStorage(badConfig)
      val action = new UploadDocumentAction(documentsRepo, brokenStorage)(using ec, Clock.systemUTC())

      action(userId, bytes, "receipt", "fail.pdf").failed.futureValue must not be null
      documentsRepo.all(userId).futureValue must be(empty)
    }
  }

  "delete document" should {
    "remove the S3 object and the document row" in {
      cleanAll()
      val userId = makeUser()
      val bytes = "delete-me".getBytes("UTF-8")
      val upload = new UploadDocumentAction(documentsRepo, storage)(using ec, Clock.systemUTC())
      val resp = upload(userId, bytes, "receipt", "del.pdf").futureValue

      val doc = documentsRepo.find(resp.id, userId).futureValue.get
      val storageKey = doc.storageKey

      val delete = new DeleteDocumentAction(documentsRepo, storage)
      delete(resp.id, userId).futureValue

      documentsRepo.find(resp.id, userId).futureValue must be(None)
      storage.get(storageKey).futureValue must be(None)
    }

    "succeed even when S3 object is already missing (idempotent)" in {
      cleanAll()
      val userId = makeUser()
      val doc = makeDocument(userId, sha256 = "orphansha", storageKey = s"data/$userId/2024-01-01/orphan")

      val delete = new DeleteDocumentAction(documentsRepo, storage)
      delete(doc.id, userId).futureValue

      documentsRepo.find(doc.id, userId).futureValue must be(None)
    }
  }

  "multi-user isolation" should {
    "namespace objects by user_id in the storage key" in {
      cleanAll()
      val user1 = makeUser()
      val user2 = makeUser()
      val action = new UploadDocumentAction(documentsRepo, storage)(using ec, Clock.systemUTC())

      val r1 = action(user1, "u1-bytes".getBytes("UTF-8"), "receipt", "u1.pdf").futureValue
      val r2 = action(user2, "u2-bytes".getBytes("UTF-8"), "receipt", "u2.pdf").futureValue

      val doc1 = documentsRepo.find(r1.id, user1).futureValue.get
      val doc2 = documentsRepo.find(r2.id, user2).futureValue.get

      doc1.storageKey must include(user1.toString)
      doc2.storageKey must include(user2.toString)
      doc1.storageKey must not be doc2.storageKey
    }
  }
}
