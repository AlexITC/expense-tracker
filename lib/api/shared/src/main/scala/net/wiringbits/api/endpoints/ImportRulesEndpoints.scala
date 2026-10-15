package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.merchants.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object ImportRulesEndpoints {
  private val baseEndpoint = endpoint
    .in("import-rules")
    .tag("ImportRules")
    .errorOut(errorResponseErrorOut)

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetImportRules.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetImportRules.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all import rules for the authenticated user")

  def create(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (CreateImportRule.Request, Future[UUID]), ErrorResponse, CreateImportRule.Response, Any] =
    baseEndpoint.post
      .in(jsonBody[CreateImportRule.Request])
      .in(userAuth)
      .out(jsonBody[CreateImportRule.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create an import rule for the authenticated user")

  def update(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UpdateImportRule.Request, Future[UUID]), ErrorResponse, UpdateImportRule.Response, Any] =
    baseEndpoint.put
      .in(path[UUID]("ruleId"))
      .in(jsonBody[UpdateImportRule.Request])
      .in(userAuth)
      .out(jsonBody[UpdateImportRule.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Update an import rule's action, pattern and merchant")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteImportRule.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("ruleId"))
      .in(userAuth)
      .out(jsonBody[DeleteImportRule.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete an import rule")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(getAll, create, update, delete)
}
