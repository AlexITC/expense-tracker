package controllers

import net.wiringbits.actions.savings.*
import net.wiringbits.api.endpoints.SavingsEndpoints
import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.savings.*
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SavingsController @Inject() (
    getSavingsGoalsAction: GetSavingsGoalsAction,
    createSavingsGoalAction: CreateSavingsGoalAction,
    updateSavingsGoalAction: UpdateSavingsGoalAction,
    deleteSavingsGoalAction: DeleteSavingsGoalAction,
    getSavingsAllocationsAction: GetSavingsAllocationsAction,
    createSavingsAllocationAction: CreateSavingsAllocationAction,
    updateSavingsAllocationAction: UpdateSavingsAllocationAction,
    deleteSavingsAllocationAction: DeleteSavingsAllocationAction,
    getSavingsEstimateAction: GetSavingsEstimateAction
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getGoals(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetSavingsGoals.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getSavingsGoalsAction(userId)
    } yield Right(response)
  }

  private def createGoal(
      request: CreateSavingsGoal.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateSavingsGoal.Response]] = handleRequest {
    logger.info(s"Create savings goal: ${request.name}")
    for {
      userId <- userIdF
      response <- createSavingsGoalAction(userId, request)
    } yield Right(response)
  }

  private def updateGoal(
      goalId: UUID,
      request: UpdateSavingsGoal.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateSavingsGoal.Response]] = handleRequest {
    logger.info(s"Update savings goal: $goalId")
    for {
      userId <- userIdF
      response <- updateSavingsGoalAction(goalId, userId, request)
    } yield Right(response)
  }

  private def deleteGoal(
      goalId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteSavingsGoal.Response]] = handleRequest {
    logger.info(s"Delete savings goal: $goalId")
    for {
      userId <- userIdF
      response <- deleteSavingsGoalAction(goalId, userId)
    } yield Right(response)
  }

  private def getAllocations(
      goalId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, GetSavingsAllocations.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getSavingsAllocationsAction(goalId, userId)
    } yield Right(response)
  }

  private def createAllocation(
      goalId: UUID,
      request: CreateSavingsAllocation.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, CreateSavingsAllocation.Response]] = handleRequest {
    logger.info(s"Create savings allocation for goal: $goalId")
    for {
      userId <- userIdF
      response <- createSavingsAllocationAction(goalId, userId, request)
    } yield Right(response)
  }

  private def updateAllocation(
      goalId: UUID,
      allocationId: UUID,
      request: UpdateSavingsAllocation.Request,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, UpdateSavingsAllocation.Response]] = handleRequest {
    logger.info(s"Update savings allocation: $allocationId")
    for {
      userId <- userIdF
      response <- updateSavingsAllocationAction(goalId, allocationId, userId, request)
    } yield Right(response)
  }

  private def deleteAllocation(
      goalId: UUID,
      allocationId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, DeleteSavingsAllocation.Response]] = handleRequest {
    logger.info(s"Delete savings allocation: $allocationId")
    for {
      userId <- userIdF
      response <- deleteSavingsAllocationAction(goalId, allocationId, userId)
    } yield Right(response)
  }

  private def getEstimate(
      merchantId: UUID,
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, GetSavingsEstimate.Response]] = handleRequest {
    for {
      userId <- userIdF
      response <- getSavingsEstimateAction(userId, merchantId)
    } yield Right(response)
  }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      SavingsEndpoints.getGoals.serverLogic(getGoals),
      SavingsEndpoints.createGoal.serverLogic(createGoal),
      SavingsEndpoints.updateGoal.serverLogic(updateGoal),
      SavingsEndpoints.deleteGoal.serverLogic(deleteGoal),
      SavingsEndpoints.getEstimate.serverLogic(getEstimate),
      SavingsEndpoints.getAllocations.serverLogic(getAllocations),
      SavingsEndpoints.createAllocation.serverLogic(createAllocation),
      SavingsEndpoints.updateAllocation.serverLogic(updateAllocation),
      SavingsEndpoints.deleteAllocation.serverLogic(deleteAllocation)
    )
  }
}
