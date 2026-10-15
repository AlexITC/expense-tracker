package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.documents.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object DocumentsEndpoints {
  private val baseEndpoint = endpoint
    .in("documents")
    .tag("Documents")
    .errorOut(errorResponseErrorOut)

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetDocuments.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetDocuments.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all documents for the authenticated user (internal use)")

  def getOne(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, GetDocument.Response, Any] =
    baseEndpoint.get
      .in(path[UUID]("documentId"))
      .in(userAuth)
      .out(jsonBody[GetDocument.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get a document for the authenticated user (internal use)")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteDocument.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("documentId"))
      .in(userAuth)
      .out(jsonBody[DeleteDocument.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a document (internal use)")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(getAll, getOne, delete)
}
