package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.BankStatementReviewLogic.{BulkRun, ReviewData}
import net.wiringbits.components.widgets.Loader
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useParams
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object BankStatementReviewPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  private val hintStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "var(--lc-brand)" }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh
    val params = useParams()
    val stmtIdOpt = Try(UUID.fromString(params.get("stmtId").getOrElse(""))).toOption

    // Kept here, not in the content, so they survive the remount a refresh causes.
    val (toast, setToast) = Hooks.useState[Option[String]](None)
    val (bulkIgnore, setBulkIgnore) = Hooks.useState[BulkRun](BulkRun.Idle)
    val (bulkImport, setBulkImport) = Hooks.useState[BulkRun](BulkRun.Idle)

    def showToast(msg: String): Unit = {
      setToast(Some(msg))
      dom.window.setTimeout(() => setToast(None), 3000)
    }

    def fetch(stmtId: UUID): Future[ReviewData] = {
      val client = props.ctx.api.client
      val statementF = client.getBankStatement(stmtId)
      val merchantsF = client.getMerchants
      val categoriesF = client.getCategories
      val rulesF = client.getImportRules
      val expensesF = statementF.flatMap { statement =>
        val (from, to) = BankStatementReviewLogic.candidateExpenseWindow(statement)
        client.getExpensesFiltered(Some(from), Some(to), unlinkedOnly = true)
      }
      for {
        statement <- statementF
        merchants <- merchantsF
        categories <- categoriesF
        rules <- rulesF
        expenses <- expensesF
      } yield ReviewData(
        statement = statement,
        merchants = merchants.data,
        categories = categories.data,
        rules = rules.data.map(ImportRuleMatcher.Rule.fromApi),
        expenses = expenses.data
      )
    }

    stmtIdOpt match {
      case None =>
        mui.Typography("Invalid statement ID").sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
      case Some(stmtId) =>
        Fragment(
          AsyncComponent[ReviewData](
            fetch = () => fetch(stmtId),
            render = data =>
              BankStatementReviewContent(
                BankStatementReviewContent.Props(
                  ctx = props.ctx,
                  data = data,
                  onRefresh = () => forceRefresh(),
                  showToast = showToast,
                  bulkIgnore = bulkIgnore,
                  setBulkIgnore = setBulkIgnore,
                  bulkImport = bulkImport,
                  setBulkImport = setBulkImport
                )
              ).withKey(timesRefreshing.toString),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
          toast match {
            case None => Fragment()
            case Some(msg) =>
              mui.Snackbar
                .open(true)
                .autoHideDuration(3000.0)
                .onClose((_, _) => setToast(None))(
                  mui.Alert.severity("success")(msg)
                )
          }
        )
    }
  }
}
