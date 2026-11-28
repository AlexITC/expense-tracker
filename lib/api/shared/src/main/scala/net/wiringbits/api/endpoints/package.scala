package net.wiringbits.api

import net.wiringbits.api.models.{ErrorResponse, errorResponseFormat}
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.EndpointInput.AuthType
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*
import sttp.tapir.model.ServerRequest

import java.util.UUID
import scala.concurrent.Future

package object endpoints {
  // TODO: better name?
  object HttpErrors {
    val badRequest: EndpointOutput.OneOfVariant[Unit] = oneOfVariant(
      statusCode(StatusCode.BadRequest).description("Invalid or missing arguments")
    )

    val unauthorized: EndpointOutput.OneOfVariant[Unit] = oneOfVariant(
      statusCode(StatusCode.Unauthorized).description("Invalid or missing authentication")
    )
  }

  val setSessionHeader: EndpointIO.Header[String] = header[String]("Set-Cookie")
    .description("Set user session")
    .schema(_.hidden(true))

  val errorResponseErrorOut: EndpointIO.Body[String, ErrorResponse] = jsonBody[ErrorResponse]
    .description("Error response")
    .example(ErrorResponse("Unauthorized: Invalid or missing authentication"))
    .schema(_.hidden(true))

  def userAuth(implicit handleAuth: ServerRequest => Future[UUID]): EndpointInput.ExtractFromRequest[Future[UUID]] =
    extractFromRequest(handleAuth)
}
