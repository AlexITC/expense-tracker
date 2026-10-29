package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.components.pages.BankStatementReviewLogic.{Tab, TabCounts}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import slinky.core.{FunctionalComponent, KeyAddingStage}

/** The row-filter tabs of the review page, each labelled with its row count. */
object BankStatementReviewTabs {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(rowFilter: Tab, counts: TabCounts, onChange: Tab => Unit)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val counts = props.counts
    mui.Tabs
      .value(props.rowFilter.key)
      .onChange((_, v) => props.onChange(Tab.parse(MuiCompat.tabValue(v))))
      .sx(new CSSPropertiesUtils { marginTop = "8px"; minHeight = "42px" })(
        mui.Tab.normal().value(Tab.Ready.key).label(s"Ready (${counts.ready})").build,
        mui.Tab.normal().value(Tab.Unprocessed.key).label(s"Unprocessed (${counts.unprocessed})").build,
        mui.Tab.normal().value(Tab.Ignored.key).label(s"Ignored (${counts.ignored})").build,
        mui.Tab.normal().value(Tab.Linked.key).label(s"Linked (${counts.linked})").build,
        mui.Tab.normal().value(Tab.All.key).label(s"All (${counts.all})").build
      )
  }
}
