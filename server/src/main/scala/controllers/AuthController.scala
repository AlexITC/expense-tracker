package controllers

import net.wiringbits.actions.auth.{GetUserAction, LoginAction, LogoutEverywhereAction}
import net.wiringbits.api.endpoints.AuthEndpoints
import net.wiringbits.api.models.*
import net.wiringbits.api.models.auth.{GetCurrentUser, Login, Logout, LogoutEverywhere}
import org.slf4j.LoggerFactory
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class AuthController @Inject() (
    loginAction: LoginAction,
    getUserAction: GetUserAction,
    logoutEverywhereAction: LogoutEverywhereAction,
    playTapirBridge: PlayTapirBridge
)(implicit ec: ExecutionContext, sessionAuthenticator: SessionAuthenticator) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def login(body: Login.Request): Future[Either[ErrorResponse, (Login.Response, String)]] =
    handleRequest {
      logger.info(s"Login API: ${body.email}")
      for {
        (response, userSession) <- loginAction(body)
        cookieEncoded <- playTapirBridge.setSession(userSession)
      } yield Right(response, cookieEncoded)
    }

  private def me(userIdF: Future[UUID]): Future[Either[ErrorResponse, GetCurrentUser.Response]] =
    handleRequest {
      for {
        userId <- userIdF
        _ = logger.info(s"Get user info: $userId")
        response <- getUserAction(userId)
      } yield Right(response)
    }

  private def logout(userIdF: Future[UUID]): Future[Either[ErrorResponse, (Logout.Response, String)]] =
    handleRequest {
      for {
        _ <- userIdF
        _ = logger.info("Logout")
        // intentionally only clears this device's cookie, other sessions stay valid (no session version bump)
        header <- playTapirBridge.clearSession()
      } yield Right(Logout.Response(), header)
    }

  private def logoutEverywhere(
      userIdF: Future[UUID]
  ): Future[Either[ErrorResponse, (LogoutEverywhere.Response, String)]] =
    handleRequest {
      for {
        userId <- userIdF
        _ = logger.info(s"Logout everywhere: $userId")
        _ <- logoutEverywhereAction(userId)
        header <- playTapirBridge.clearSession()
      } yield Right(LogoutEverywhere.Response(), header)
    }

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] = {
    List(
      AuthEndpoints.login.serverLogic(login),
      AuthEndpoints.getCurrentUser.serverLogic(me),
      AuthEndpoints.logout.serverLogic(logout),
      AuthEndpoints.logoutEverywhere.serverLogic(logoutEverywhere)
    )
  }
}
