package net.wiringbits.actions.documents

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.documents.UploadDocument
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.DocumentsRepository
import net.wiringbits.repositories.models.{BackgroundJobData, Document}
import net.wiringbits.services.BlobStorage
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UploadDocumentAction @Inject() (
    documentsRepository: DocumentsRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, bytes: Array[Byte], kindStr: String, filename: String): Future[UploadDocument.Response] = {
    val kind = DocumentKind
      .withNameOption(kindStr)
      .getOrElse(throw new RuntimeException(s"Unsupported document kind: $kindStr"))
    val sha256 = computeSha256(bytes)
    for {
      existingOpt <- documentsRepository.findBySha256(userId, sha256)
      response <- existingOpt match {
        case Some(existing) =>
          logger.info(s"Upload dedup: sha256=$sha256 already exists as document ${existing.id}, returning existing")
          Future.successful(UploadDocument.Response(existing.id))
        case None =>
          val now = clock.instant()
          val uploadDate = LocalDate.now(clock)
          val storageKey = s"data/$userId/$uploadDate/$sha256"
          val documentId = UuidCreator.getTimeOrderedEpoch()
          logger.info(s"Uploading document: kind=$kindStr filename=$filename sha256=$sha256 storageKey=$storageKey")
          val document = Document.Create(
            id = documentId,
            userId = userId,
            sha256 = sha256,
            storageKey = storageKey,
            kind = kind,
            filename = filename,
            createdAt = now,
            updatedAt = now
          )
          val job = BackgroundJobData.Create(
            id = UUID.randomUUID(),
            `type` = BackgroundJobType.ProcessDocument,
            payload = BackgroundJobPayload.ProcessDocument(documentId),
            status = BackgroundJobStatus.Pending,
            executeAt = now,
            createdAt = now,
            updatedAt = now
          )
          for {
            alreadyUploaded <- blobStorage.exists(storageKey)
            _ <-
              if (alreadyUploaded) {
                logger.info(s"S3 object already exists at $storageKey, skipping upload")
                Future.unit
              } else {
                logger.info(s"Putting S3 object at $storageKey (${bytes.length} bytes)")
                blobStorage.put(storageKey, bytes)
              }
            storedId <- documentsRepository.createIfAbsent(document, job)
          } yield UploadDocument.Response(storedId)
      }
    } yield response
  }

  private def computeSha256(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map("%02x".format(_)).mkString
  }
}
