package net.wiringbits.components.pages.bankstatementreview

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.components.pages.bankstatements.{AccountAliasEditor, BankStatementsLogic}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import slinky.core.facade.{Fragment, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import scala.concurrent.Future

/** The review page title row (filename, row count, actions) and the statement metadata line. */
object BankStatementReviewHeader {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      statement: GetBankStatement.Response,
      rowCount: Int,
      currentAlias: String,
      regenerateDisabled: Boolean,
      onRegenerate: () => Unit,
      onSaveAlias: String => Future[Unit]
  )

  /** The account the statement belongs to, by its display label (nickname included when set), and its nickname editor.
    */
  private def accountLine(
      stmt: GetBankStatement.Response,
      currentAlias: String,
      onSaveAlias: String => Future[Unit]
  ): ReactElement =
    val label = BankStatementsLogic.accountLabel(
      bankName = stmt.bankName,
      productType = stmt.productType,
      accountLast4 = stmt.accountLast4,
      currency = stmt.currency,
      alias = Option(currentAlias).filter(_.nonEmpty)
    )
    mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"; alignItems = "center"; flexWrap = "wrap"; gap = "12px"; marginBottom = "2px"
    })(
      mui
        .Typography(s"Account: $label")
        .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" }),
      AccountAliasEditor(AccountAliasEditor.Props(currentAlias = currentAlias, onSave = onSaveAlias))
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
          .onClick(_ => history.push(BankStatementReviewLogic.backToListPath(stmt)))
      ),
      accountLine(stmt, props.currentAlias, props.onSaveAlias),
      statementMetadataLine(stmt)
    )
  }
}
