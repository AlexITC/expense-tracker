package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.categories.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object CategoriesEndpoints {
  private val baseEndpoint = endpoint
    .in("categories")
    .tag("Categories")
    .errorOut(errorResponseErrorOut)

  def getAll(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetCategories.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetCategories.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get all categories for the authenticated user")

  def create(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (CreateCategory.Request, Future[UUID]), ErrorResponse, CreateCategory.Response, Any] =
    baseEndpoint.post
      .in(jsonBody[CreateCategory.Request])
      .in(userAuth)
      .out(jsonBody[CreateCategory.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create a category for the authenticated user")

  def update(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UpdateCategory.Request, Future[UUID]), ErrorResponse, UpdateCategory.Response, Any] =
    baseEndpoint.put
      .in(path[UUID]("categoryId"))
      .in(jsonBody[UpdateCategory.Request])
      .in(userAuth)
      .out(jsonBody[UpdateCategory.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Rename a category")

  def delete(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteCategory.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("categoryId"))
      .in(userAuth)
      .out(jsonBody[DeleteCategory.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a category")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(getAll, create, update, delete)
}
