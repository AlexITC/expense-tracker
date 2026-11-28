import net.wiringbits.actions.auth.ValidateSessionAction
import net.wiringbits.api.models.{ErrorResponse, errorResponseFormat}
import net.wiringbits.models.UserSession
import org.slf4j.LoggerFactory
import play.api.mvc.request.DefaultRequestFactory
import play.api.mvc.{CookieHeaderEncoding, RequestHeader, Session}
import sttp.tapir.model.ServerRequest

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

package object controllers {
  private val logger = LoggerFactory.getLogger(this.getClass)

  class PlayTapirBridge @Inject() (
      requestFactory: DefaultRequestFactory,
      cookieHeaderEncoding: CookieHeaderEncoding
  )(implicit ec: ExecutionContext) {
    def setSession(userSession: UserSession): Future[String] = Future {
      val session = Session(
        Map("id" -> userSession.userId.toString, "sessionVersion" -> userSession.version.toString)
      )
      val playCookie = requestFactory.sessionBaker.encodeAsCookie(session)
      cookieHeaderEncoding.encodeSetCookieHeader(List(playCookie))
    }

    def clearSession(): Future[String] = Future {
      val encoded = requestFactory.sessionBaker.discard.toCookie
      cookieHeaderEncoding.encodeSetCookieHeader(List(encoded))
    }
  }

  class SessionAuthenticator @Inject() (validateSessionAction: ValidateSessionAction)(implicit ec: ExecutionContext) {

    // This is the way to access the play request from tapir, we need it to extract the play session
    // UUID has to be future, because we want to handle the exception in the controllers
    // tapir exposes the underlying request as Any, so reaching the Play RequestHeader needs a cast.
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    implicit val authHandler: ServerRequest => Future[UUID] = serverRequest =>
      val session = serverRequest.underlying
        .asInstanceOf[RequestHeader]
        .session

      // cookies without a sessionVersion (e.g. issued before session versioning existed) are rejected
      def userSessionFromCookie = Future {
        val maybe = for {
          userId <- session.get("id").flatMap(str => Try(UUID.fromString(str)).toOption)
          version <- session.get("sessionVersion").flatMap(_.toIntOption)
        } yield UserSession(userId, version)
        maybe.getOrElse(throw new RuntimeException("Invalid or missing authentication"))
      }

      userSessionFromCookie
        .flatMap(validateSessionAction.apply)
        .recover { case NonFatal(_) =>
          throw new RuntimeException("Unauthorized: Invalid or missing authentication")
        }
  }

  // Tapir endpoints take the authentication handler implicitly, controllers only declare the authenticator.
  implicit def authHandler(implicit authenticator: SessionAuthenticator): ServerRequest => Future[UUID] =
    authenticator.authHandler

  def handleRequest[R](
      block: Future[Right[ErrorResponse, R]]
  )(implicit ec: ExecutionContext): Future[Either[ErrorResponse, R]] = {
    block.recover(errorHandler)
  }

  def errorHandler[R]: PartialFunction[Throwable, Left[ErrorResponse, R]] = {
    // rendering any error this way should be enough for a while
    case NonFatal(ex) =>
      // debug level used because this includes any validation error as well as server errors
      logger.debug(s"Error response while handling a request: ${ex.getMessage}", ex)
      Left(ErrorResponse(ex.getMessage))
  }
}
