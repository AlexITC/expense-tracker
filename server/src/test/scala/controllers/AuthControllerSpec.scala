package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.auth.Login
import net.wiringbits.api.models.users.VerifyEmail
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.*
import net.wiringbits.config.UserTokensConfig
import net.wiringbits.repositories.UserTokensRepository
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{CookieHeaderEncoding, Session, SessionCookieBaker}
import sttp.client3.{HttpClientFutureBackend, UriContext, basicRequest}
import utils.LoginUtils
import utils.RandomData.randomEmail

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import java.util.UUID
import scala.concurrent.Future

class AuthControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

  def userTokensRepository: UserTokensRepository = app.injector.instanceOf(classOf[UserTokensRepository])

  private val clock = mock[Clock]
  when(clock.instant).thenReturn(Instant.now())

  private val tokenGenerator = mock[TokenGenerator]

  private val emailApi = mock[EmailApi]
  when(emailApi.sendEmail(any[EmailRequest]())).thenReturn(Future.unit)

  private val captchaApi = mock[ReCaptchaApi]
  when(captchaApi.verify(any[Captcha]())).thenReturn(Future.successful(true))

  override def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    super
      .guiceApplicationBuilder(container)
      .overrides(
        inject.bind[EmailApi].to(emailApi),
        inject.bind[ReCaptchaApi].to(captchaApi),
        inject.bind[Clock].to(clock),
        inject.bind[TokenGenerator].to(tokenGenerator)
      )

  def userTokensConfig: UserTokensConfig = app.injector.instanceOf(classOf[UserTokensConfig])

  "POST /auth/login" should {
    "return the response from a correct user" in withApiClient { implicit client =>
      val name = Name.trusted("wiringbits")
      val email = randomEmail()
      val loginResponse =
        createVerifyLoginUser(tokenGenerator, nameMaybe = Some(name), emailMaybe = Some(email)).futureValue

      loginResponse.name must be(name)
      loginResponse.email must be(email)
    }

    "fail when the user tries to login without an email verification" in withApiClient { implicit client =>
      val password = Password.trusted("test123...")
      val user = createUser(passwordMaybe = Some(password)).futureValue

      val loginRequest = Login.Request(
        email = user.email,
        password = password,
        captcha = Captcha.trusted("test")
      )

      val error = client
        .login(loginRequest)
        .expectError

      error must be("The email is not verified, check your spam folder if you don't see the email.")
    }

    "fail when the user tries to verify with a wrong token" in withApiClient { implicit client =>
      val user = createUser().futureValue

      val error = client
        .verifyEmail(VerifyEmail.Request(UserToken(user.id, UUID.randomUUID())))
        .expectError

      error must be(s"Token for user ${user.id} wasn't found")
    }

    "fail when the user tries to verify with an expired token" in withApiClient { implicit client =>
      val verificationToken = UUID.randomUUID()
      when(tokenGenerator.next()).thenReturn(verificationToken)

      val user = createUser().futureValue

      when(clock.instant).thenReturn(Instant.now().plus(2, ChronoUnit.DAYS))

      val error = client
        .verifyEmail(VerifyEmail.Request(UserToken(user.id, verificationToken)))
        .expectError

      error must be("Token is expired")
    }

    "login after successful email confirmation" in withApiClient { implicit client =>
      val email = randomEmail()
      val response = createVerifyLoginUser(tokenGenerator, emailMaybe = Some(email)).futureValue

      response.email must be(email)
    }

    "fail when password is incorrect" in withApiClient { implicit client =>
      val verificationToken = UUID.randomUUID()
      when(tokenGenerator.next()).thenReturn(verificationToken)

      val user = createUser().futureValue

      client.verifyEmail(VerifyEmail.Request(UserToken(user.id, verificationToken))).futureValue

      val loginRequest = Login.Request(
        email = user.email,
        password = Password.trusted("Incorrect password"),
        captcha = Captcha.trusted("test")
      )

      val error = client
        .login(loginRequest)
        .expectError

      error must be("The given email/password doesn't match")
    }

    "fail when the captcha isn't valid" in withApiClient { implicit client =>
      val password = Password.trusted("test123...")
      val user = createUser(passwordMaybe = Some(password)).futureValue

      val loginRequest = Login.Request(
        email = user.email,
        password = password,
        captcha = Captcha.trusted("test")
      )

      when(captchaApi.verify(any[Captcha]())).thenReturn(Future.successful(false))

      val error = client
        .login(loginRequest)
        .expectError

      error must be("Invalid captcha, try again")

      when(captchaApi.verify(any[Captcha]())).thenReturn(Future.successful(true))
    }

    "fail when user isn't email confirmed" in withApiClient { implicit client =>
      val password = Password.trusted("test123...")
      val user = createUser(passwordMaybe = Some(password)).futureValue

      val loginRequest = Login.Request(
        email = user.email,
        password = password,
        captcha = Captcha.trusted("test")
      )

      val error = client
        .login(loginRequest)
        .expectError

      error must be("The email is not verified, check your spam folder if you don't see the email.")
    }
  }

  "GET /auth/me" should {
    "return current logged user" in withApiClient { implicit client =>
      val name = Name.trusted("wiringbits")
      val email = randomEmail()
      createVerifyLoginUser(tokenGenerator, nameMaybe = Some(name), emailMaybe = Some(email)).futureValue
      val currentUser = client.currentUser.futureValue

      currentUser.name must be(name)
      currentUser.email must be(email)
    }

    "fail if user isn't logged in" in withApiClient { client =>
      val error = client.currentUser.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }

    "reject a session cookie without a session version" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id

      val body = getMeWithSession(Map("id" -> userId.toString))

      body must include("Unauthorized: Invalid or missing authentication")
    }

    "accept a session cookie with the current session version" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id

      val body = getMeWithSession(Map("id" -> userId.toString, "sessionVersion" -> "0"))

      body must include(userId.toString)
    }

    "reject a session cookie with a stale session version" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id

      val body = getMeWithSession(Map("id" -> userId.toString, "sessionVersion" -> "1"))

      body must include("Unauthorized: Invalid or missing authentication")
    }
  }

  "POST /auth/logout" should {
    "keep other sessions of the same user signed in" in withApiClient { implicit clientA =>
      val email = randomEmail()
      val user = createVerifyLoginUser(tokenGenerator, emailMaybe = Some(email)).futureValue

      withApiClient { clientB =>
        clientB.login(Login.Request(email, Password.trusted("test123..."), Captcha.trusted("test"))).futureValue

        clientA.logout.futureValue

        clientA.currentUser.expectError must be("Unauthorized: Invalid or missing authentication")
        clientB.currentUser.futureValue.id must be(user.id)
      }
    }
  }

  "play.http.session.maxAge" should {
    "be 30 days" in {
      app.configuration.get[scala.concurrent.duration.FiniteDuration]("play.http.session.maxAge") must be(
        scala.concurrent.duration.Duration(30, "days")
      )
    }
  }

  private def getMeWithSession(data: Map[String, String]): String = {
    val cookie = app.injector.instanceOf[SessionCookieBaker].encodeAsCookie(Session(data))
    val cookieHeader = app.injector.instanceOf[CookieHeaderEncoding].encodeCookieHeader(Seq(cookie))
    val backend = HttpClientFutureBackend()
    basicRequest
      .get(uri"http://localhost:$port/auth/me")
      .header("Cookie", cookieHeader)
      .send(backend)
      .futureValue
      .body
      .merge
  }
}
