package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import slinky.core.facade.{Fragment, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

/** The review page title row (filename, row count, actions) and the statement metadata line. */
object BankStatementReviewHeader {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      statement: GetBankStatement.Response,
      rowCount: Int,
      regenerateDisabled: Boolean,
      onRegenerate: () => Unit
  )

  /** A one-line summary of the statement metadata. */
  private def statementMetadataLine(stmt: GetBankStatement.Response): ReactElement = {
    val productType = stmt.productType match {
      case "credit" => "Type: Credit card"
      case "debit" => "Type: Debit account"
      case other => s"Type: $other"
    }
    val parts = List(
      s"Bank: ${stmt.bankName}",
      productType,
      s"Currency: ${stmt.currency}",
      s"Account: ••••${stmt.accountLast4}",
      s"Period: ${stmt.periodStart} – ${stmt.periodEnd}"
    )
    mui
      .Typography(parts.mkString("    ·    "))
      .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)"; marginBottom = "8px" })
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val history = useHistory()
    val stmt = props.statement

    Fragment(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; alignItems = "baseline"; gap = "12px"; marginBottom = "4px"
      })(
        mui
          .Typography(stmt.documentFilename)
          .sx(new CSSPropertiesUtils {
            fontSize = "18px"; fontWeight = "700"; color = "var(--lc-text)"
            fontFamily = "\"IBM Plex Sans\", sans-serif"
          }),
        mui
          .Typography(s"${props.rowCount} transactions")
          .sx(new CSSPropertiesUtils { fontSize = "14px"; color = "var(--lc-text-2)" }),
        mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
        mui.Button
          .normal()(
            if (props.regenerateDisabled) "Regenerating…"
            else "Regenerate AI suggestions"
          )
          .size("small")
          .variant("outlined")
          .disabled(props.regenerateDisabled)
          .onClick(_ => props.onRegenerate()),
        mui.Button
          .normal()("Auto-reconcile")
          .size("small")
          .variant("outlined")
          .onClick(_ => history.push(s"/bank-statements/${stmt.id}/auto-reconcile")),
        mui.Button
          .normal()("← Back")
          .size("small")
          .variant("outlined")
          .onClick(_ => history.push("/bank-statements"))
      ),
      statementMetadataLine(stmt)
    )
  }
}
