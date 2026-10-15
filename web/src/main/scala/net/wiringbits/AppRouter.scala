package net.wiringbits

import net.wiringbits.components.pages.{
  AboutPage,
  BankStatementAutoReconcilePage,
  BankStatementReviewPage,
  BankStatementTemplatePage,
  BankStatementsPage,
  BulkExpenseCreationPage,
  BulkMerchantSetupPage,
  CategoriesPage,
  ChartsPage,
  DashboardPage,
  ExpensesPage,
  ForgotPasswordPage,
  HomePage,
  ImportRulesPage,
  MerchantReviewPage,
  MerchantsPage,
  ReceiptsPage,
  RefundsPage,
  ReportsPage,
  ResendVerifyEmailPage,
  ResetPasswordPage,
  SavingsPage,
  SignInPage,
  SignUpPage,
  UserEditPage,
  VerifyEmailPage,
  VerifyEmailWithTokenPage
}
import net.wiringbits.components.widgets.{AppBar, AuthenticatedLayout, Footer}
import net.wiringbits.core.ReactiveHooks
import net.wiringbits.models.{AuthState, User}
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Scaffold
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.{Redirect, Route, Switch}
import slinky.core.facade.ReactElement
import slinky.core.{FunctionalComponent, KeyAddingStage}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object AppRouter {
  def apply(ctx: AppContext): KeyAddingStage =
    component(Props(ctx = ctx))

  case class Props(ctx: AppContext)

  private def route(path: String, ctx: AppContext)(child: => ReactElement): ReactElement = {
    Route(path = path, exact = true)(
      Scaffold(
        appbar = Some(AppBar(ctx)),
        body = child,
        footer = Some(Footer(ctx))
      )
    )
  }

  private def authRoute(path: String, ctx: AppContext, user: User)(child: => ReactElement): ReactElement = {
    Route(path = path, exact = true)(
      AuthenticatedLayout(ctx, user, child)
    )
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val auth = ReactiveHooks.useDistinctValue(props.ctx.$auth)
    val home = route("/", props.ctx)(HomePage(props.ctx))
    val about = route("/about", props.ctx)(AboutPage(props.ctx))
    val signIn = route("/signin", props.ctx)(SignInPage(props.ctx))
    val signUp = route("/signup", props.ctx)(SignUpPage(props.ctx))
    val email = route("/verify-email", props.ctx)(VerifyEmailPage(props.ctx))
    val emailCode = route("/verify-email/:emailCode", props.ctx)(VerifyEmailWithTokenPage(props.ctx))
    val forgotPassword = route("/forgot-password", props.ctx)(ForgotPasswordPage(props.ctx))
    val resetPassword = route("/reset-password/:resetPasswordCode", props.ctx)(ResetPasswordPage(props.ctx))
    val resendVerifyEmail = route("/resend-verify-email", props.ctx)(ResendVerifyEmailPage(props.ctx))

    def dashboard(user: User) = authRoute("/dashboard", props.ctx, user)(DashboardPage(props.ctx, user))
    def me(user: User) = authRoute("/me", props.ctx, user)(UserEditPage(props.ctx, user))
    def categories(user: User) = authRoute("/categories", props.ctx, user)(CategoriesPage(props.ctx, user))
    def merchants(user: User) = authRoute("/merchants", props.ctx, user)(MerchantsPage(props.ctx, user))
    def merchantReview(user: User) = authRoute("/merchant-review", props.ctx, user)(MerchantReviewPage(props.ctx, user))
    def importRules(user: User) = authRoute("/import-rules", props.ctx, user)(ImportRulesPage(props.ctx, user))
    def expenses(user: User) = authRoute("/expenses", props.ctx, user)(ExpensesPage(props.ctx, user))
    def refunds(user: User) = authRoute("/refunds", props.ctx, user)(RefundsPage(props.ctx, user))
    def receipts(user: User) = authRoute("/receipts", props.ctx, user)(ReceiptsPage(props.ctx, user))
    def bankStatements(user: User) = authRoute("/bank-statements", props.ctx, user)(BankStatementsPage(props.ctx, user))
    def bankStatementDraftReview(user: User) =
      authRoute("/bank-statements/import/:draftId", props.ctx, user)(BankStatementTemplatePage(props.ctx, user))
    def bulkExpenseCreation(user: User) =
      authRoute("/bank-statements/bulk-expenses", props.ctx, user)(BulkExpenseCreationPage(props.ctx, user))
    def bulkMerchantSetup(user: User) =
      authRoute("/bank-statements/bulk-setup", props.ctx, user)(BulkMerchantSetupPage(props.ctx, user))
    def bankStatementReview(user: User) =
      authRoute("/bank-statements/:stmtId", props.ctx, user)(BankStatementReviewPage(props.ctx, user))
    def bankStatementAutoReconcile(user: User) =
      authRoute("/bank-statements/:stmtId/auto-reconcile", props.ctx, user)(
        BankStatementAutoReconcilePage(props.ctx, user)
      )
    def reports(user: User) = authRoute("/reports", props.ctx, user)(ReportsPage(props.ctx, user))
    def savings(user: User) = authRoute("/savings", props.ctx, user)(SavingsPage(props.ctx, user))
    def charts(user: User) = authRoute("/charts", props.ctx, user)(ChartsPage(props.ctx, user))
    val signOut = route("/signout", props.ctx) {
      props.ctx.api.client.logout.onComplete {
        case Success(_) =>
          props.ctx.loggedOut()
          println("Logged out successfully")

        case Failure(exception) =>
          println(s"Failed to log out: ${exception.getMessage}")
      }

      Redirect("/")
    }

    val catchAllRoute = Route(path = "*")(render = Redirect("/"))

    auth match {
      case AuthState.Unauthenticated =>
        Switch(
          home,
          about,
          signIn,
          signUp,
          email,
          emailCode,
          forgotPassword,
          resetPassword,
          resendVerifyEmail,
          catchAllRoute
        )

      case AuthState.Authenticated(user) =>
        val homeRedirect = Route(path = "/", exact = true)(render = Redirect("/expenses"))
        Switch(
          homeRedirect,
          me(user),
          dashboard(user),
          categories(user),
          merchants(user),
          merchantReview(user),
          importRules(user),
          expenses(user),
          refunds(user),
          receipts(user),
          bulkExpenseCreation(user),
          bulkMerchantSetup(user),
          bankStatementAutoReconcile(user),
          bankStatementReview(user),
          bankStatementDraftReview(user),
          bankStatements(user),
          savings(user),
          reports(user),
          charts(user),
          about,
          signOut,
          catchAllRoute
        )
    }
  }
}
