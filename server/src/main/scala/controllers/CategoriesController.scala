package controllers

import net.wiringbits.actions.categories.*
import net.wiringbits.api.endpoints.CategoriesEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.categories.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CategoriesController @Inject() (
    getCategoriesAction: GetCategoriesAction,
    createCategoryAction: CreateCategoryAction,
    updateCategoryAction: UpdateCategoryAction,
    deleteCategoryAction: DeleteCategoryAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetCategories.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getCategoriesAction(userId)
    } yield Right(response)
  }

  private def create(
      request: CreateCategory.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateCategory.Response]] = handleRequest {
    logger.info(s"Create category: ${request.name}")
    for {
      userId <- userIdF
      response <- createCategoryAction(userId, request)
    } yield Right(response)
  }

  private def update(
      categoryId: UUID,
      request: UpdateCategory.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateCategory.Response]] = handleRequest {
    logger.info(s"Update category: $categoryId")
    for {
      userId <- userIdF
      response <- updateCategoryAction(categoryId, userId, request)
    } yield Right(response)
  }

  private def delete(
      categoryId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteCategory.Response]] = handleRequest {
    logger.info(s"Delete category: $categoryId")
    for {
      userId <- userIdF
      response <- deleteCategoryAction(categoryId, userId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      CategoriesEndpoints.getAll.serverLogic(getAll),
      CategoriesEndpoints.create.serverLogic(create),
      CategoriesEndpoints.update.serverLogic(update),
      CategoriesEndpoints.delete.serverLogic(delete)
    )
  }
}
