package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import slinky.core.{FunctionalComponent, KeyAddingStage}

/** The "From statement" card shown above the drawer forms: description, date and signed amount. */
object BankStatementRowSummary {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(row: GetBankStatement.StatementRowData)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val row = props.row
    mui.Box.sx(new CSSPropertiesUtils {
      padding = "12px 16px"; marginBottom = "16px"; borderRadius = "8px"
      border = "1px solid var(--lc-border)"; backgroundColor = "var(--lc-bg)"
    })(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; justifyContent = "space-between"; alignItems = "flex-start"
      })(
        mui.Box(
          mui
            .Typography("From statement")
            .sx(new CSSPropertiesUtils {
              fontSize = "11px"; fontWeight = "700"; letterSpacing = "0.06em"
              textTransform = "uppercase"; color = "var(--lc-text-2)"; display = "block"
            }),
          mui
            .Typography(row.description)
            .sx(new CSSPropertiesUtils {
              fontFamily = "\"IBM Plex Mono\", monospace"; fontSize = "13.5px"
              marginTop = "4px"; display = "block"
            }),
          mui
            .Typography(row.date.toString)
            .sx(new CSSPropertiesUtils {
              fontSize = "12px"; color = "var(--lc-text-2)"; display = "block"
            })
        ),
        mui
          .Typography(s"${if (row.amount > 0) "+" else ""}${row.amount}")
          .sx(new CSSPropertiesUtils {
            fontWeight = "700"; fontSize = "18px"
            fontFamily = "\"IBM Plex Mono\", monospace"
            color = if (row.amount > 0) "var(--lc-brand)" else "var(--lc-error)"
          })
      )
    )
  }
}
