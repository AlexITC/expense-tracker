package net.wiringbits.api.endpoints

import net.wiringbits.api.models.ErrorResponse
import net.wiringbits.api.models.savings.*
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

object SavingsEndpoints {
  private val baseEndpoint = endpoint
    .in("savings")
    .tag("Savings")
    .errorOut(errorResponseErrorOut)

  def getGoals(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, Future[UUID], ErrorResponse, GetSavingsGoals.Response, Any] =
    baseEndpoint.get
      .in(userAuth)
      .out(jsonBody[GetSavingsGoals.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("List all savings goals with balance and progress")

  def createGoal(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (CreateSavingsGoal.Request, Future[UUID]), ErrorResponse, CreateSavingsGoal.Response, Any] =
    baseEndpoint.post
      .in(jsonBody[CreateSavingsGoal.Request])
      .in(userAuth)
      .out(jsonBody[CreateSavingsGoal.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Create a savings goal")

  def updateGoal(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, UpdateSavingsGoal.Request, Future[UUID]), ErrorResponse, UpdateSavingsGoal.Response, Any] =
    baseEndpoint.put
      .in(path[UUID]("goalId"))
      .in(jsonBody[UpdateSavingsGoal.Request])
      .in(userAuth)
      .out(jsonBody[UpdateSavingsGoal.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Update a savings goal")

  def deleteGoal(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, DeleteSavingsGoal.Response, Any] =
    baseEndpoint.delete
      .in(path[UUID]("goalId"))
      .in(userAuth)
      .out(jsonBody[DeleteSavingsGoal.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a savings goal and all its allocations")

  def getAllocations(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, GetSavingsAllocations.Response, Any] =
    baseEndpoint.get
      .in(path[UUID]("goalId") / "allocations")
      .in(userAuth)
      .out(jsonBody[GetSavingsAllocations.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("List allocations for a savings goal")

  def createAllocation(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, CreateSavingsAllocation.Request, Future[UUID]),
    ErrorResponse,
    CreateSavingsAllocation.Response,
    Any
  ] =
    baseEndpoint.post
      .in(path[UUID]("goalId") / "allocations")
      .in(jsonBody[CreateSavingsAllocation.Request])
      .in(userAuth)
      .out(jsonBody[CreateSavingsAllocation.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Add an allocation (positive or negative) to a savings goal")

  def updateAllocation(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, UUID, UpdateSavingsAllocation.Request, Future[UUID]),
    ErrorResponse,
    UpdateSavingsAllocation.Response,
    Any
  ] =
    baseEndpoint.put
      .in(path[UUID]("goalId") / "allocations" / path[UUID]("allocationId"))
      .in(jsonBody[UpdateSavingsAllocation.Request])
      .in(userAuth)
      .out(jsonBody[UpdateSavingsAllocation.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Update a savings allocation")

  def deleteAllocation(implicit authHandler: ServerRequest => Future[UUID]): Endpoint[
    Unit,
    (UUID, UUID, Future[UUID]),
    ErrorResponse,
    DeleteSavingsAllocation.Response,
    Any
  ] =
    baseEndpoint.delete
      .in(path[UUID]("goalId") / "allocations" / path[UUID]("allocationId"))
      .in(userAuth)
      .out(jsonBody[DeleteSavingsAllocation.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Delete a savings allocation")

  def getEstimate(implicit
      authHandler: ServerRequest => Future[UUID]
  ): Endpoint[Unit, (UUID, Future[UUID]), ErrorResponse, GetSavingsEstimate.Response, Any] =
    baseEndpoint.get
      .in("estimate")
      .in(query[UUID]("merchantId"))
      .in(userAuth)
      .out(jsonBody[GetSavingsEstimate.Response])
      .errorOut(oneOf(HttpErrors.badRequest))
      .summary("Get a typical spend estimate for a merchant based on expense history")

  def routes(implicit authHandler: ServerRequest => Future[UUID]): List[AnyEndpoint] =
    List(
      getGoals,
      createGoal,
      updateGoal,
      deleteGoal,
      getEstimate,
      getAllocations,
      createAllocation,
      updateAllocation,
      deleteAllocation
    )
}
