package controllers

import net.wiringbits.actions.documents.{DeleteDocumentAction, GetDocumentAction, GetDocumentsAction}
import net.wiringbits.api.endpoints.DocumentsEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.documents.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DocumentsController @Inject() (
    getDocumentsAction: GetDocumentsAction,
    getDocumentAction: GetDocumentAction,
    deleteDocumentAction: DeleteDocumentAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetDocuments.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getDocumentsAction(userId)
    } yield Right(response)
  }

  private def getOne(
      documentId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, GetDocument.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getDocumentAction(documentId, userId)
    } yield Right(response)
  }

  private def delete(
      documentId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteDocument.Response]] = handleRequest {
    logger.info(s"Delete document: $documentId")
    for {
      userId <- userIdF
      response <- deleteDocumentAction(documentId, userId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      DocumentsEndpoints.getAll.serverLogic(getAll),
      DocumentsEndpoints.getOne.serverLogic(getOne),
      DocumentsEndpoints.delete.serverLogic(delete)
    )
  }
}
