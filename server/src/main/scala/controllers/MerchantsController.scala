package controllers

import net.wiringbits.actions.merchants.*
import net.wiringbits.api.endpoints.MerchantsEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.merchants.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class MerchantsController @Inject() (
    getMerchantsAction: GetMerchantsAction,
    createMerchantAction: CreateMerchantAction,
    updateMerchantAction: UpdateMerchantAction,
    deleteMerchantAction: DeleteMerchantAction,
    getPendingMerchantsAction: GetPendingMerchantsAction,
    reviewMerchantAction: ReviewMerchantAction,
    markMerchantAsDuplicateAction: MarkMerchantAsDuplicateAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getAll(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetMerchants.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getMerchantsAction(userId)
    } yield Right(response)
  }

  private def create(
      request: CreateMerchant.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateMerchant.Response]] = handleRequest {
    logger.info(s"Create merchant: ${request.name}")
    for {
      userId <- userIdF
      response <- createMerchantAction(userId, request)
    } yield Right(response)
  }

  private def update(
      merchantId: UUID,
      request: UpdateMerchant.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateMerchant.Response]] = handleRequest {
    logger.info(s"Update merchant: $merchantId")
    for {
      userId <- userIdF
      response <- updateMerchantAction(merchantId, userId, request)
    } yield Right(response)
  }

  private def delete(
      merchantId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteMerchant.Response]] = handleRequest {
    logger.info(s"Delete merchant: $merchantId")
    for {
      userId <- userIdF
      response <- deleteMerchantAction(merchantId, userId)
    } yield Right(response)
  }

  private def getPending(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetPendingMerchants.Response]] =
    handleRequest {
      for {
        userId <- userIdF
        response <- getPendingMerchantsAction(userId)
      } yield Right(response)
    }

  private def review(
      merchantId: UUID,
      request: ReviewMerchant.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, ReviewMerchant.Response]] = handleRequest {
    logger.info(s"Review merchant: $merchantId")
    for {
      userId <- userIdF
      response <- reviewMerchantAction(merchantId, userId, request)
    } yield Right(response)
  }

  private def markAsDuplicate(
      merchantId: UUID,
      request: MarkMerchantAsDuplicate.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, MarkMerchantAsDuplicate.Response]] = handleRequest {
    logger.info(s"Mark merchant $merchantId as duplicate of ${request.realMerchantId}")
    for {
      userId <- userIdF
      response <- markMerchantAsDuplicateAction(merchantId, userId, request)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      MerchantsEndpoints.getAll.serverLogic(getAll),
      MerchantsEndpoints.create.serverLogic(create),
      MerchantsEndpoints.update.serverLogic(update),
      MerchantsEndpoints.delete.serverLogic(delete),
      MerchantsEndpoints.getPending.serverLogic(getPending),
      MerchantsEndpoints.review.serverLogic(review),
      MerchantsEndpoints.markAsDuplicate.serverLogic(markAsDuplicate)
    )
  }
}
