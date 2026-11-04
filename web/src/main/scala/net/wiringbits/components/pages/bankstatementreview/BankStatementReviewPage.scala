package net.wiringbits.components.pages.bankstatementreview

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic.BulkRun
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useParams
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.util.Try

object BankStatementReviewPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  private val hintStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "var(--lc-brand)" }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val params = useParams()
    val stmtIdOpt = Try(UUID.fromString(params.get("stmtId").getOrElse(""))).toOption

    // Kept here, not in the content, so the page owns them while the content is keyed by statement.
    val (toast, setToast) = Hooks.useState[Option[String]](None)
    val (bulkIgnore, setBulkIgnore) = Hooks.useState[BulkRun](BulkRun.Idle)
    val (bulkImport, setBulkImport) = Hooks.useState[BulkRun](BulkRun.Idle)

    def showToast(msg: String): Unit = {
      setToast(Some(msg))
      dom.window.setTimeout(() => setToast(None), 3000)
    }

    stmtIdOpt match {
      case None =>
        mui.Typography("Invalid statement ID").sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
      case Some(stmtId) =>
        Fragment(
          BankStatementReviewContent(
            BankStatementReviewContent.Props(
              ctx = props.ctx,
              stmtId = stmtId,
              showToast = showToast,
              bulkIgnore = bulkIgnore,
              setBulkIgnore = setBulkIgnore,
              bulkImport = bulkImport,
              setBulkImport = setBulkImport
            )
          ).withKey(stmtId.toString),
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
