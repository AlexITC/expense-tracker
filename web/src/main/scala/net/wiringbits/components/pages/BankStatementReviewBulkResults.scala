package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.components.pages.BankStatementReviewPage.BulkResults
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import slinky.core.facade.{Fragment, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID

/** One line per row processed by a bulk import or ignore, coloured by outcome. */
object BankStatementReviewBulkResults {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(results: BulkResults, descById: Map[UUID, String])

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    mui.Box.sx(new CSSPropertiesUtils { marginTop = "16px" })(
      Fragment(
        props.results.map { case (rowId, outcome) =>
          val lbl = BankStatementReviewLogic.resultLabel(rowId, props.descById, outcome)
          val txtColor = outcome match {
            case Right(_) => "var(--lc-success-text)"
            case Left(_) => "var(--lc-error)"
          }
          (mui
            .Typography(lbl)
            .sx(new CSSPropertiesUtils { fontSize = "12px"; color = txtColor })
            .withKey(rowId.toString): ReactElement)
        }*
      )
    )
  }
}
