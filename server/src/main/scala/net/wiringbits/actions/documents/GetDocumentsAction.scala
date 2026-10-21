package net.wiringbits.actions.documents

import net.wiringbits.api.models.documents.GetDocuments
import net.wiringbits.repositories.DocumentsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetDocumentsAction @Inject() (documentsRepository: DocumentsRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetDocuments.Response] = {
    documentsRepository.all(userId).map { docs =>
      val items = docs.map { doc =>
        GetDocuments.Response.Document(
          id = doc.id,
          sha256 = doc.sha256,
          kind = doc.kind.entryName,
          filename = doc.filename,
          createdAt = doc.createdAt
        )
      }
      GetDocuments.Response(items)
    }
  }
}
