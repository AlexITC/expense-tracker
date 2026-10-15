package net.wiringbits.actions.documents

import net.wiringbits.api.models.documents.DeleteDocument
import net.wiringbits.repositories.DocumentsRepository
import net.wiringbits.services.BlobStorage

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteDocumentAction @Inject() (documentsRepository: DocumentsRepository, blobStorage: BlobStorage)(implicit
    ec: ExecutionContext
) {

  def apply(documentId: UUID, userId: UUID): Future[DeleteDocument.Response] = {
    for {
      docOpt <- documentsRepository.find(documentId, userId)
      doc = docOpt.getOrElse(throw new RuntimeException("Document not found"))
      // S3 delete is idempotent — missing object is treated as success by S3BlobStorage.
      // Skip for legacy rows that predate storage (empty storageKey).
      _ <- if (doc.storageKey.nonEmpty) blobStorage.delete(doc.storageKey) else Future.unit
      _ <- documentsRepository.delete(documentId, userId)
    } yield DeleteDocument.Response(documentId)
  }
}
