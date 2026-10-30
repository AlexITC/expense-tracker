package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.components.pages.BankStatementReviewLogic.BulkRun
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import slinky.core.facade.ReactElement
import slinky.core.{FunctionalComponent, KeyAddingStage}

/** The bar shown above the table while rows are selected, with the bulk process/ignore actions and their progress. */
object BankStatementReviewBulkBar {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      selectedCount: Int,
      bulkActive: Boolean,
      importRun: BulkRun,
      ignoreRun: BulkRun,
      onProcess: () => Unit,
      onIgnore: () => Unit,
      onClear: () => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    mui.Box.sx(new CSSPropertiesUtils {
      margin = "12px 24px 0 24px"; padding = "8px 16px"; borderRadius = "8px"
      display = "flex"; alignItems = "center"; gap = "12px"
      backgroundColor = "var(--lc-brand)"
    })(
      mui
        .Typography(s"${props.selectedCount} selected")
        .sx(new CSSPropertiesUtils { fontWeight = "600"; fontSize = "14px"; color = "var(--lc-brand-on)" }),
      mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
      props.importRun match {
        case r: BulkRun.Running =>
          mui
            .Typography(s"Processing ${r.done}/${r.total}${if (r.failed > 0) s" (${r.failed} failed)" else ""}…")
            .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)"; fontSize = "14px" })
        case _ =>
          (mui.Button
            .normal()("Process ready")
            .size("small")
            .variant("contained")
            .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-paper)"; color = "var(--lc-brand)" })
            .disabled(!props.bulkActive)
            .onClick(_ => props.onProcess())
            .build: ReactElement)
      },
      props.ignoreRun match {
        case r: BulkRun.Running =>
          mui
            .Typography(s"Ignoring ${r.done}/${r.total}${if (r.failed > 0) s" (${r.failed} failed)" else ""}…")
            .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)"; fontSize = "14px" })
        case _ =>
          (mui.Button
            .normal()("Ignore")
            .size("small")
            .variant("text")
            .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)" })
            .disabled(!props.bulkActive)
            .onClick(_ => props.onIgnore())
            .build: ReactElement)
      },
      (mui.IconButton
        .normal()(
          mui.Typography("×").sx(new CSSPropertiesUtils { fontSize = "20px"; color = "var(--lc-brand-on)" })
        )
        .size("small")
        .onClick(_ => props.onClear())
        .build: ReactElement)
    )
  }
}
